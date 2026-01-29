package com.hotvect.onlineutils.concurrency.fileutils;

import com.hotvect.onlineutils.concurrency.CpuIntensiveAggregator;
import io.micrometer.core.instrument.MeterRegistry;
import com.hotvect.utils.VerboseCallable;

import java.io.File;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.hotvect.onlineutils.concurrency.fileutils.FileUtils.readData;

public class CpuIntensiveFileAggregator<Z> extends VerboseCallable<Z> {
    private final MeterRegistry meterRegistry;
    private final int numThread;
    private final int queueSize;
    private final int batchSize;
    private final List<File> source;
    private final Supplier<Z> init;
    private final BiFunction<Z, String, Z> merge;

    public static <Z> CpuIntensiveFileAggregator<Z> aggregator(MeterRegistry meterRegistry,
                                                               List<File> source,
                                                               Supplier<Z> init,
                                                               BiFunction<Z, String, Z> merge,
                                                               int numThreads, int queueSize, int batchSize) {
        return new CpuIntensiveFileAggregator<>(meterRegistry, source, init, merge, numThreads, queueSize, batchSize);
    }

    public static <Z> CpuIntensiveFileAggregator<Z> aggregator(MeterRegistry meterRegistry,
                                                               List<File> source,
                                                               Supplier<Z> init,
                                                               BiFunction<Z, String, Z> merge) {
        return new CpuIntensiveFileAggregator<>(meterRegistry,
                source,
                init,
                merge,
                (Runtime.getRuntime().availableProcessors() > 1 ? Runtime.getRuntime().availableProcessors() - 1 : 1),
                (int) (Runtime.getRuntime().availableProcessors() * 3.0),
                100);

    }


    private CpuIntensiveFileAggregator(MeterRegistry meterRegistry,
                                       List<File> source,
                                       Supplier<Z> init,
                                       BiFunction<Z, String, Z> merge,
                                       int numThreads, int queueSize, int batchSize) {
        this.meterRegistry = meterRegistry;
        this.queueSize = queueSize;
        this.batchSize = batchSize;
        this.numThread = numThreads;
        this.source = source;
        this.init = init;
        this.merge = merge;
    }

    @Override
    protected Z doCall() {
        CpuIntensiveAggregator<Z, String> processor = new CpuIntensiveAggregator<>(meterRegistry, init, merge, numThread, queueSize, batchSize);
        try (Stream<String> source = readData(this.source)) {
            return processor.aggregate(source);
        }
    }
}
