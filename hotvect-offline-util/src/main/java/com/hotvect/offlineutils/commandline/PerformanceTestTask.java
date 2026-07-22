package com.hotvect.offlineutils.commandline;


import com.hotvect.onlineutils.concurrency.CpuIntensiveAggregator;
import io.micrometer.core.instrument.MeterRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.UniformReservoir;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.google.common.util.concurrent.RateLimiter;
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
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.util.MathUtils;
import com.hotvect.onlineutils.util.StreamUtils;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.concurrency.fileutils.FileFormat;
import com.hotvect.onlineutils.concurrency.fileutils.FileUtils;
import com.hotvect.onlineutils.concurrency.fileutils.RecordReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

public class PerformanceTestTask<EXAMPLE extends Example<? extends OfflineRequest, ?>, ALGO extends Algorithm> extends Task {
    private static final ObjectMapper OM = new ObjectMapper();
    static final int DEFAULT_SAMPLE_POOL_SIZE = 3_000;
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestTask.class);

    protected PerformanceTestTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }


    @Override
    protected Map<String, Object> perform() throws Exception {
        WorkloadMode workloadMode = resolveWorkloadMode(offlineTaskContext.options().performanceTestWorkloadMode);
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());
        AlgorithmInstanceFactory algoAlgorithmInstanceFactory = new AlgorithmInstanceFactory(
                offlineTaskContext.classLoader(),
                ExecutionContext.of(workloadMode, InputSemantic.OFFLINE),
                false
        );
        ExampleDecoder<EXAMPLE> decoder = algorithmSupporterFactory.getTestDecoder(offlineTaskContext.algorithmDefinition());


        try (AlgorithmInstance<ALGO> algoAlgorithmInstance = algoAlgorithmInstanceFactory.load(
                this.offlineTaskContext.algorithmDefinition(),
                this.offlineTaskContext.options().parameters,
                Map.of()
        )) {
            LOGGER.info("Loaded AlgorithmInstance:{}", algoAlgorithmInstance);
            Options options = offlineTaskContext.options();

            checkState(
                    this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                            this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                    ,
                    "Only one source file type is supported for performance test"
            );

            checkState(
                    options.samplePoolSize == -1 || options.samplePoolSize > 0,
                    "--sample-pool-size must be > 0 (or left unset), got: %s",
                    options.samplePoolSize
            );

            final int samplePoolSize = pickSamplePoolSize(options);
            final int oversampleFactor = 3;
            final long samplingSeed = 42L;
            final int linesPerFile = 200;
            final int minFilesToTouch = 20;

            List<EXAMPLE> sampledData = sampleDecodedExamples(
                    super.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                    decoder,
                    samplePoolSize,
                    oversampleFactor,
                    samplingSeed,
                    linesPerFile,
                    minFilesToTouch
            );

            // Warm up
            Map<String, Double> warmUpResult = performTestRun(sampledData.stream(), algoAlgorithmInstance.algorithm(), sampledData.size());
            double meanThroughput = warmUpResult.get("mean_throughput");

            checkState(Double.isFinite(meanThroughput) && meanThroughput > 0.0, "Warmup throughput must be positive, got: %s", meanThroughput);
            checkState(
                    Double.isFinite(options.targetThroughputFraction) && options.targetThroughputFraction >= 0.0 && options.targetThroughputFraction <= 1.0,
                    "--target-throughput-fraction must be within [0, 1], got: %s",
                    options.targetThroughputFraction
            );
            checkState(
                    options.targetRps == -1.0 || (Double.isFinite(options.targetRps) && options.targetRps > 0.0),
                    "--target-rps must be > 0 (or left unset), got: %s",
                    options.targetRps
            );

            Double targetRps = null;
            if (options.targetRps > 0.0) {
                targetRps = options.targetRps;
            } else if (options.targetThroughputFraction > 0.0) {
                targetRps = meanThroughput * options.targetThroughputFraction;
            }
            if (targetRps != null) {
                logger.info("Pacing performance test at {} rps (warmup mean_throughput={} targetThroughputFraction={})",
                        targetRps, meanThroughput, options.targetThroughputFraction);
            } else {
                logger.info("No pacing configured for performance test (warmup mean_throughput={})", meanThroughput);
            }

            double sampleSizingThroughput = targetRps == null ? meanThroughput : targetRps;
            int samplePerTest = pickSamplePerTest(options, sampleSizingThroughput);
            logger.info("Using sample size {} for the performance test", samplePerTest);

            // Actual measurement
            List<Map<String, Double>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                RateLimiter rateLimiter = targetRps == null ? null : RateLimiter.create(targetRps);
                results.add(performTestRun(
                        StreamUtils.repeatToLength(sampledData, samplePerTest),
                        algoAlgorithmInstance.algorithm(),
                        samplePerTest,
                        rateLimiter
                ));
            }

            Map<String, Object> metadata = new HashMap<>();

            Map<String, Object> aggregatedPerformanceTestResult = aggregate(results);
            metadata.put("response_time_metrics", aggregatedPerformanceTestResult);
            metadata.put("warmup_mean_throughput", meanThroughput);
            metadata.put("target_rps", targetRps);
            metadata.put("requested_sample_pool_size", samplePoolSize);
            metadata.put("sample_pool_size", sampledData.size());
            metadata.put("samples", samplePerTest);
            metadata.put("workload_mode", workloadMode.name().toLowerCase(Locale.ROOT));
            return metadata;
        }
    }

    static WorkloadMode resolveWorkloadMode(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return WorkloadMode.REALTIME;
        }
        return switch (configuredValue.trim().toLowerCase(Locale.ROOT)) {
            case "realtime" -> WorkloadMode.REALTIME;
            case "batch" -> WorkloadMode.BATCH;
            default -> throw new IllegalArgumentException(
                    "Unsupported performance-test workload mode: " + configuredValue + ". Expected one of: realtime, batch"
            );
        };
    }

    static <T> List<T> sampleDecodedExamples(
            List<File> sources,
            Function<String, List<T>> decoder,
            int sampleSize,
            int oversampleFactor,
            long seed,
            int linesPerFile,
            int minFilesToTouch
    ) throws Exception {
        checkState(sampleSize > 0, "sampleSize must be positive");
        checkState(oversampleFactor > 0, "oversampleFactor must be positive");
        checkState(linesPerFile > 0, "linesPerFile must be positive");
        checkState(minFilesToTouch >= 0, "minFilesToTouch must be non-negative");

        List<File> files = new ArrayList<>(FileUtils.listFiles(sources).toList());
        checkState(!files.isEmpty(), "No source files found for performance test sampling");

        FileFormat format = FileFormat.validateUniformFormat(files);
        checkState(format == FileFormat.TEXT, "Performance test sampling expects text inputs, found: %s", format);

        List<Path> rootPaths = sources.stream().map(File::toPath).map(Path::toAbsolutePath).map(Path::normalize).toList();
        Map<File, String> fileKeyByFile = new HashMap<>(files.size() * 2);
        for (File file : files) {
            fileKeyByFile.put(file, fileKey(file, rootPaths));
        }

        files.sort(Comparator.comparing(fileKeyByFile::get));
        Collections.shuffle(files, new Random(seed));

        int targetDecoded = Math.max(sampleSize, sampleSize * oversampleFactor);
            ReservoirSample<T> reservoirSample = reservoirSampleDecodedExamples(
                    files,
                    decoder,
                    sampleSize,
                    targetDecoded,
                    seed,
                    linesPerFile,
                    minFilesToTouch
            );
            List<T> sampled = reservoirSample.sample();

            checkState(!sampled.isEmpty(), "Performance test sampling did not decode any examples");

            if (sampled.size() < sampleSize) {
                logger.warn(
                    "Only sampled {} examples (requested {}): candidates={} filesTouched={} sweeps={}",
                    sampled.size(),
                    sampleSize,
                    reservoirSample.candidatesSeen(),
                    reservoirSample.maxFilesTouched(),
                    reservoirSample.sweeps()
            );
        } else {
            logger.info(
                    "Sampled {} examples from {} candidate examples across {} files (sweeps={})",
                    sampled.size(),
                    reservoirSample.candidatesSeen(),
                    reservoirSample.maxFilesTouched(),
                    reservoirSample.sweeps()
            );
        }
        return sampled;
    }

    static <T> ReservoirSample<T> reservoirSampleDecodedExamples(
            List<File> files,
            Function<String, List<T>> decoder,
            int sampleSize,
            int targetDecoded,
            long seed,
            int linesPerFile,
            int minFilesToTouch
    ) throws Exception {
        checkState(sampleSize > 0, "sampleSize must be positive");
        checkState(targetDecoded > 0, "targetDecoded must be positive");
        checkState(linesPerFile > 0, "linesPerFile must be positive");
        checkState(minFilesToTouch >= 0, "minFilesToTouch must be non-negative");

        int effectiveMinFilesToTouch = Math.min(minFilesToTouch, files.size());
        List<T> sample = new ArrayList<>(sampleSize);
        Random reservoirRandom = new Random(seed);
        int candidatesSeen = 0;
        int maxFilesTouched = 0;
        int sweeps = 0;
        List<RecordReader<String>> readers = new ArrayList<>(Collections.nCopies(files.size(), null));
        boolean[] exhausted = new boolean[files.size()];

        try {
            while (true) {
                boolean minFilesSatisfied = maxFilesTouched >= effectiveMinFilesToTouch;
                if (minFilesSatisfied && candidatesSeen >= targetDecoded) {
                    break;
                }

                int candidatesBeforeSweep = candidatesSeen;
                for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
                    maxFilesTouched = Math.max(maxFilesTouched, fileIndex + 1);
                    minFilesSatisfied = maxFilesTouched >= effectiveMinFilesToTouch;

                    if (exhausted[fileIndex]) {
                        if (minFilesSatisfied && candidatesSeen >= targetDecoded) {
                            break;
                        }
                        continue;
                    }

                    RecordReader<String> reader = readers.get(fileIndex);
                    if (reader == null) {
                        reader = RecordReader.create(files.get(fileIndex));
                        readers.set(fileIndex, reader);
                    }

                    for (int i = 0; i < linesPerFile && reader.hasNext() && candidatesSeen < targetDecoded; i++) {
                        List<T> decoded = decoder.apply(reader.next());
                        if (decoded == null || decoded.isEmpty()) {
                            continue;
                        }
                        for (T decodedExample : decoded) {
                            if (candidatesSeen >= targetDecoded) {
                                break;
                            }
                            candidatesSeen++;
                            if (sample.size() < sampleSize) {
                                sample.add(decodedExample);
                            } else {
                                int replacementIndex = reservoirRandom.nextInt(candidatesSeen);
                                if (replacementIndex < sampleSize) {
                                    sample.set(replacementIndex, decodedExample);
                                }
                            }
                        }
                    }

                    if (!reader.hasNext()) {
                        reader.close();
                        readers.set(fileIndex, null);
                        exhausted[fileIndex] = true;
                    }

                    if (minFilesSatisfied && candidatesSeen >= targetDecoded) {
                        break;
                    }
                }

                if (candidatesSeen == candidatesBeforeSweep) {
                    break;
                }
                sweeps++;
            }
        } finally {
            closeReaders(readers);
        }

        Collections.shuffle(sample, new Random(seed));
        return new ReservoirSample<>(sample, candidatesSeen, maxFilesTouched, sweeps);
    }

    static <T> SamplingCandidates<T> collectDecodedCandidates(
            List<File> files,
            Function<String, List<T>> decoder,
            int targetDecoded,
            int linesPerFile,
            int minFilesToTouch
    ) throws Exception {
        checkState(targetDecoded > 0, "targetDecoded must be positive");
        int effectiveMinFilesToTouch = Math.min(minFilesToTouch, files.size());

        List<T> candidates = new ArrayList<>(Math.min(targetDecoded, 100_000));
        int maxFilesTouched = 0;
        int sweeps = 0;
        List<RecordReader<String>> readers = new ArrayList<>(Collections.nCopies(files.size(), null));
        boolean[] exhausted = new boolean[files.size()];

        try {
            while (true) {
                boolean minFilesSatisfied = maxFilesTouched >= effectiveMinFilesToTouch;
                if (minFilesSatisfied && candidates.size() >= targetDecoded) {
                    break;
                }

                int candidatesBeforeSweep = candidates.size();
                for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
                    maxFilesTouched = Math.max(maxFilesTouched, fileIndex + 1);
                    minFilesSatisfied = maxFilesTouched >= effectiveMinFilesToTouch;

                    if (exhausted[fileIndex]) {
                        if (minFilesSatisfied && candidates.size() >= targetDecoded) {
                            break;
                        }
                        continue;
                    }

                    RecordReader<String> reader = readers.get(fileIndex);
                    if (reader == null) {
                        reader = RecordReader.create(files.get(fileIndex));
                        readers.set(fileIndex, reader);
                    }

                    for (int i = 0; i < linesPerFile && reader.hasNext() && candidates.size() < targetDecoded; i++) {
                        List<T> decoded = decoder.apply(reader.next());
                        if (decoded == null || decoded.isEmpty()) {
                            continue;
                        }
                        int remaining = targetDecoded - candidates.size();
                        if (decoded.size() <= remaining) {
                            candidates.addAll(decoded);
                        } else {
                            candidates.addAll(decoded.subList(0, remaining));
                        }
                    }

                    if (!reader.hasNext()) {
                        reader.close();
                        readers.set(fileIndex, null);
                        exhausted[fileIndex] = true;
                    }

                    if (minFilesSatisfied && candidates.size() >= targetDecoded) {
                        break;
                    }
                }

                if (candidates.size() == candidatesBeforeSweep) {
                    break;
                }
                sweeps++;
            }
        } finally {
            closeReaders(readers);
        }
        return new SamplingCandidates<>(candidates, maxFilesTouched, sweeps);
    }

    record SamplingCandidates<T>(List<T> candidates, int maxFilesTouched, int sweeps) {}
    record ReservoirSample<T>(List<T> sample, int candidatesSeen, int maxFilesTouched, int sweeps) {}

    private static void closeReaders(List<RecordReader<String>> readers) throws Exception {
        Exception firstException = null;
        for (RecordReader<String> reader : readers) {
            if (reader == null) {
                continue;
            }
            try {
                reader.close();
            } catch (Exception e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
            }
        }
        if (firstException != null) {
            throw firstException;
        }
    }

    private static String fileKey(File file, List<Path> rootPaths) {
        Path filePath = file.toPath().toAbsolutePath().normalize();
        for (int i = 0; i < rootPaths.size(); i++) {
            Path root = rootPaths.get(i);
            if (filePath.startsWith(root)) {
                Path relative = root.relativize(filePath);
                String relativeString = relative.toString();
                if (relativeString.isEmpty() && filePath.getFileName() != null) {
                    relativeString = filePath.getFileName().toString();
                }
                relativeString = relativeString.replace('\\', '/');
                return i + ":" + relativeString;
            }
        }
        return "?:"
                + filePath.getFileName(); // fallback; should not happen if files come from the given sources
    }

    private int pickSamplePerTest(Options options, double meanThroughputPerSec) {
        if(options.samples > 0){
            return options.samples;
        } else {
            // Constraint the sample size to be between 2000 and 200000 to avoid extreme estimations
            return (int) Math.max(Math.min(meanThroughputPerSec * 2 * 60 ,200000), 2000);
        }
    }

    static int pickSamplePoolSize(Options options) {
        if (options.samplePoolSize > 0) {
            return options.samplePoolSize;
        }
        return options.samples > 0 ? Math.min(options.samples, DEFAULT_SAMPLE_POOL_SIZE) : DEFAULT_SAMPLE_POOL_SIZE;
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
        return performTestRun(data, algo, sampleSize, null);
    }

    private Map<String, Double> performTestRun(Stream<EXAMPLE> data, ALGO algo, int sampleSize, RateLimiter rateLimiter) throws Exception {
        MeterRegistry meterRegistry = super.offlineTaskContext.meterRegistry();
        UniformReservoir uniformReservoir = new UniformReservoir(sampleSize);
        Timer timer = new Timer(uniformReservoir);
        Function<EXAMPLE, Void> sink = getSink(algo, timer, rateLimiter);

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

    private Function<EXAMPLE, Void> getSink(ALGO algo, Timer responseTimer, RateLimiter rateLimiter) {
        if (algo instanceof Ranker<?, ?>) {
            Ranker ranker = (Ranker) algo;
            return example -> {
                RankingExample<?, ?, ?> rankingExample = (RankingExample<?, ?, ?>) example;
                if (rateLimiter != null) {
                    rateLimiter.acquire();
                }
                try (var ignored = responseTimer.time()) {
                    var result = ranker.rank(rankingExample.rankingRequest());
                    consume(result);
                    return null;
                }
            };
        } else if (algo instanceof BulkScorer bulkScorer){
            return example -> {
                RankingExample<?, ?, ?> rankingExample = (RankingExample<?, ?, ?>) example;
                if (rateLimiter != null) {
                    rateLimiter.acquire();
                }
                try (var ignored = responseTimer.time()) {
                    var result = bulkScorer.score(rankingExample.rankingRequest());
                    consume(result);
                    return null;
                }
            };

        } else if (algo instanceof TopK topK) {
            return example -> {
                TopKExample<?, ?, ?> topKExample = (TopKExample<?, ?, ?>) example;
                if (rateLimiter != null) {
                    rateLimiter.acquire();
                }
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
