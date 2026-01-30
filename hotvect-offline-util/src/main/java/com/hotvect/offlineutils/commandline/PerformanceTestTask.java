package com.hotvect.offlineutils.commandline;


import com.hotvect.onlineutils.concurrency.CpuIntensiveAggregator;
import io.micrometer.core.instrument.MeterRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.UniformReservoir;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.*;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.util.MathUtils;
import com.hotvect.onlineutils.util.StreamUtils;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static com.hotvect.onlineutils.concurrency.fileutils.FileUtils.readData;
import static java.lang.Math.max;

public class PerformanceTestTask<EXAMPLE extends Example<? extends OfflineRequest, ?>, ALGO extends Algorithm> extends Task {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestTask.class);

    protected PerformanceTestTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }


    @Override
    protected Map<String, Object> perform() throws Exception {
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());
        AlgorithmInstanceFactory algoAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                offlineTaskContext.classLoader(),
                false
        );
        ExampleDecoder<EXAMPLE> decoder = algorithmSupporterFactory.getTestDecoder(offlineTaskContext.algorithmDefinition());



        AlgorithmInstance<ALGO> algoAlgorithmInstance = algoAlgorithmInstanceFactory.load(
                this.offlineTaskContext.algorithmDefinition(),
                this.offlineTaskContext.options().parameters,
                Map.of()
        );

        LOGGER.info("Loaded AlgorithmInstance:{}", algoAlgorithmInstance);

        checkState(
                this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for performance test"
        );


        final int sampleSize = 5000;
        com.hotvect.onlineutils.util.UniformReservoir<String> exampleReservoir = new com.hotvect.onlineutils.util.UniformReservoir<>(5000);
        Stream<String> data = readData(super.offlineTaskContext.options().sourceFiles.values().iterator().next());
        data.forEach(exampleReservoir::update);
        List<EXAMPLE> sampledData = exampleReservoir.getSnapshot().stream()
                .flatMap(x -> decoder.apply(x).stream())
                .toList();
        // Warm up
        Map<String, Double> warmUpResult = performTestRun(sampledData.stream(), algoAlgorithmInstance.algorithm(), sampleSize);
        double mean_throughput = warmUpResult.get("mean_throughput");

        int samplePerTest = pickSamplePerTest(offlineTaskContext.options(), mean_throughput);
        logger.info("Using sample size {} for the performance test", samplePerTest);


        // Actual measurement
        List<Map<String, Double>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(performTestRun(StreamUtils.repeatToLength(sampledData, samplePerTest), algoAlgorithmInstance.algorithm(), samplePerTest));
        }

        Map<String, Object> metadata = new HashMap<>();

        Map<String, Object> aggregatedPerformanceTestResult = aggregate(results);
        metadata.put("response_time_metrics", aggregatedPerformanceTestResult);
        return metadata;
    }

    private int pickSamplePerTest(Options options, double meanThroughputPerSec) {
        if(options.samples > 0){
            return options.samples;
        } else {
            // Constraint the sample size to be between 2000 and 200000 to avoid extreme estimations
            return (int) Math.max(Math.min(meanThroughputPerSec * 2 * 60 ,200000), 2000);
        }
    }

    private Map<String, Object> aggregate(List<Map<String, Double>> results) {
        Map<String, List<Double>> grouped = results.stream().flatMap(x -> x.entrySet().stream()).collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> ImmutableList.of(e.getValue()),
                (li1, li2) -> Stream.concat(li1.stream(), li2.stream()).collect(Collectors.toList())
        ));

        return Maps.transformValues(grouped, s -> MathUtils.meanWithConfidenceIntervals(s, 0.95));
    }


    private Map<String, Double> performTestRun(Stream<EXAMPLE> data, ALGO algo, int sampleSize) throws Exception {
        MeterRegistry meterRegistry = super.offlineTaskContext.meterRegistry();
        UniformReservoir uniformReservoir = new UniformReservoir(sampleSize);
        Timer timer = new Timer(uniformReservoir);
        Function<EXAMPLE, Void> sink = getSink(algo, timer);

        CpuIntensiveAggregator<Integer, EXAMPLE> processor = new CpuIntensiveAggregator<>(
                meterRegistry,
                () -> 0,
                (_acc, record) -> {
                    sink.apply(record);
                    return _acc;
                },
                this.offlineTaskContext.options().maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.options().maxThreads,
                this.offlineTaskContext.options().queueLength,
                this.offlineTaskContext.options().batchSize
        );

        processor.aggregate(data);
        var snapshot = timer.getSnapshot();
        ImmutableMap.Builder<String, Double> result = ImmutableMap.builder();
        result.put("p50", snapshot.getMedian());
        result.put("p75", snapshot.get75thPercentile());
        result.put("p95", snapshot.get95thPercentile());
        result.put("p99", snapshot.get99thPercentile());
        result.put("p999", snapshot.get999thPercentile());
        result.put("mean", snapshot.getMean());
        result.put("mean_throughput", timer.getMeanRate());
        result.put("count", (double) timer.getCount());
        return result.build();
    }

    private Function<EXAMPLE, Void> getSink(ALGO algo, Timer responseTimer) {
        if (algo instanceof Ranker<?, ?>) {
            Ranker ranker = (Ranker) algo;
            return example -> {
                RankingExample<?, ?, ?> rankingExample = (RankingExample<?, ?, ?>) example;
                try (var ignored = responseTimer.time()) {
                    var result = ranker.rank(rankingExample.rankingRequest());
                    consume(result);
                    return null;
                }
            };
        } else if (algo instanceof BulkScorer bulkScorer){
            return example -> {
                RankingExample<?, ?, ?> rankingExample = (RankingExample<?, ?, ?>) example;
                try (var ignored = responseTimer.time()) {
                    var result = bulkScorer.bulkScore(rankingExample.rankingRequest());
                    consume(result);
                    return null;
                }
            };

        } else if (algo instanceof TopK topK) {
            return example -> {
                TopKExample<?, ?, ?> topKExample = (TopKExample<?, ?, ?>) example;
                try (var ignored = responseTimer.time()) {
                    var result = topK.apply(topKExample.request());
                    consume(result);
                    return null;
                }
            };

        } else {
            throw new AssertionError("Unknown algorithm type:" + algo.getClass().getCanonicalName());
        }
    }

    private void consume(Object result) {
        // A poor man's Blackhole (https://github.com/openjdk/jmh/blob/master/jmh-core/src/main/java/org/openjdk/jmh/infra/Blackhole.java)
        // Described in Tim Peierls, Brian Goetz, Joshua Bloch, Joseph Bowbeer, Doug Lea, and David Holmes. 2005. Java Concurrency in Practice. Addison-Wesley Professional.

        if (System.nanoTime() == System.identityHashCode(result)) {
            System.out.println();
        }
    }

}
