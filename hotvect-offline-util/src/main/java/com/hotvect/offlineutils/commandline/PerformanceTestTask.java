package com.hotvect.offlineutils.commandline;


import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
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
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.scoring.ScoringExample;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.transformation.memoization.MemoizationStatistic;
import com.hotvect.offlineutils.concurrent.CpuIntensiveAggregator;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.offlineutils.util.MathUtils;
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
import static com.hotvect.offlineutils.util.FileUtils.readData;
import static java.lang.Math.max;

public class PerformanceTestTask<EXAMPLE extends Example, ALGO extends Algorithm> extends Task {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestTask.class);

    protected PerformanceTestTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }


    @Override
    protected Map<String, Object> perform() throws Exception {
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.getClassLoader());
        AlgorithmInstanceFactory algoAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                offlineTaskContext.getClassLoader(),
                false
        );
        ExampleDecoder<EXAMPLE> decoder = algorithmSupporterFactory.getTestDecoder(offlineTaskContext.getAlgorithmDefinition());



        AlgorithmInstance<ALGO> algoAlgorithmInstance = algoAlgorithmInstanceFactory.load(
                this.offlineTaskContext.getAlgorithmDefinition(),
                this.offlineTaskContext.getOptions().parameters
        );

        LOGGER.info("Loaded AlgorithmInstance:{}", algoAlgorithmInstance);

        checkState(
                this.offlineTaskContext.getOptions().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.getOptions().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for performance test"
        );


        int warmUpSize = 2000;
        Stream<EXAMPLE> warmupData = readData(super.offlineTaskContext.getOptions().sourceFiles.values().iterator().next())
                .limit(warmUpSize)
                .parallel()
                .flatMap(x -> decoder.apply(x).stream());

        // Warm up
        Map<String, Double> warmUpResult = performTestRun(warmupData, algoAlgorithmInstance.getAlgorithm(), warmUpSize);
        double mean_throughput = warmUpResult.get("mean_throughput");

        int sampleSize = pickSampleSize(offlineTaskContext.getOptions(), mean_throughput);
        logger.info("Using sample size {} for the performance test", sampleSize);
        // We collect the lines into a list in order to front load the file IO
        // So that it does not affect the performance test
        List<EXAMPLE> inputRecords = readData(super.offlineTaskContext.getOptions().sourceFiles.values().iterator().next())
                .limit(sampleSize)
                .parallel()
                .flatMap(x -> decoder.apply(x).stream())
                .collect(Collectors.toList());


        if(offlineTaskContext.getOptions().collectMemoizationStatistics){
            logger.info("Collecting memoization statistics");
            MemoizationStatistic.reset();
        }

        // Actual measurement
        List<Map<String, Double>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(performTestRun(inputRecords.stream(), algoAlgorithmInstance.getAlgorithm(), sampleSize));
        }

        Map<String, Object> metadata = new HashMap<>();
        if(offlineTaskContext.getOptions().collectMemoizationStatistics){
            metadata.put("memoization_statistics", MemoizationStatistic.result());
        }

        Meter mainMeter = super.offlineTaskContext.getMetricRegistry().meter(MetricRegistry.name(CpuIntensiveAggregator.class, "record"));
        Map<String, Object> aggregatedPerformanceTestResult = aggregate(results);
        metadata.put("response_time_metrics", aggregatedPerformanceTestResult);
        metadata.put("mean_throughput", mainMeter.getMeanRate());
        metadata.put("total_record_count", mainMeter.getCount());
        return metadata;
    }

    private int pickSampleSize(Options options, double meanThroughputPerSec) {
        if(options.samples > 0){
            return options.samples;
        } else {
            // Constraint the sample size to be between 2000 and 50000 to avoid extreme estimations
            return (int) Math.max(Math.min(meanThroughputPerSec * 2 * 60 ,50000), 2000);
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
        UniformReservoir uniformReservoir = new UniformReservoir(sampleSize);
        Timer timer = new Timer(uniformReservoir);
        Function<EXAMPLE, Void> sink = getSink(algo, timer);


        CpuIntensiveAggregator<Integer, EXAMPLE> processor = new CpuIntensiveAggregator<>(
                super.offlineTaskContext.getMetricRegistry(),
                () -> 0,
                (_acc, record) -> {
                    sink.apply(record);
                    return _acc;
                },
                this.offlineTaskContext.getOptions().maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.getOptions().maxThreads,
                this.offlineTaskContext.getOptions().queueLength,
                this.offlineTaskContext.getOptions().batchSize
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
        if (algo instanceof Scorer<?>) {
            Scorer scorer = (Scorer) algo;
            return example -> {
                ScoringExample<?, ?> scoringExample = (ScoringExample<?, ?>) example;
                try (var ignored = responseTimer.time()) {
                    var result = Double.valueOf(scorer.applyAsDouble(scoringExample.getRecord()));
                    consume(result);
                    return null;
                }
            };
        } else if (algo instanceof Ranker<?, ?>) {
            Ranker ranker = (Ranker) algo;
            return example -> {
                RankingExample<?, ?, ?> rankingExample = (RankingExample<?, ?, ?>) example;
                try (var ignored = responseTimer.time()) {
                    var result = ranker.rank(rankingExample.getRankingRequest());
                    consume(result);
                    return null;
                }
            };
        } else if (algo instanceof BulkScorer){
            BulkScorer bulkScorer = (BulkScorer) algo;
            return example -> {
                RankingExample<?, ?, ?> rankingExample = (RankingExample<?, ?, ?>) example;
                try (var ignored = responseTimer.time()) {
                    var result = bulkScorer.apply(rankingExample.getRankingRequest());
                    consume(result);
                    return null;
                }
            };

        } else if (algo instanceof TopK) {
            TopK topK = (TopK) algo;
            return example -> {
                TopKExample<?, ?, ?> rankingExample = (TopKExample<?, ?, ?>) example;
                try (var ignored = responseTimer.time()) {
                    var result = topK.apply(rankingExample.getTopKRequest());
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
