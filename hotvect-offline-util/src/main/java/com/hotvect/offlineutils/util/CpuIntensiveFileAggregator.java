package com.hotvect.offlineutils.util;

import com.codahale.metrics.MetricRegistry;
import com.hotvect.offlineutils.concurrent.CpuIntensiveAggregator;
import com.hotvect.utils.VerboseCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.hotvect.offlineutils.util.FileUtils.readData;

public class CpuIntensiveFileAggregator<Z> extends VerboseCallable<Z> {
    private final MetricRegistry metricRegistry;
    private final int numThread;
    private final int queueSize;
    private final int batchSize;
    private final List<File> source;
    private final Supplier<Z> init;
    private final BiFunction<Z, String, Z> merge;

    public static <Z> CpuIntensiveFileAggregator<Z> aggregator(MetricRegistry metricRegistry,
                                                               List<File> source,
                                                               Supplier<Z> init,
                                                               BiFunction<Z, String, Z> merge,
                                                               int numThreads, int queueSize, int batchSize) {
        return new CpuIntensiveFileAggregator<>(metricRegistry, source, init, merge, numThreads, queueSize, batchSize);
    }

    public static <Z> CpuIntensiveFileAggregator<Z> aggregator(MetricRegistry metricRegistry,
                                                               List<File> source,
                                                               Supplier<Z> init,
                                                               BiFunction<Z, String, Z> merge) {
        return new CpuIntensiveFileAggregator<>(metricRegistry,
                source,
                init,
                merge,
                (Runtime.getRuntime().availableProcessors() > 1 ? Runtime.getRuntime().availableProcessors() - 1 : 1),
                (int) (Runtime.getRuntime().availableProcessors() * 3.0),
                100);

    }


    private CpuIntensiveFileAggregator(MetricRegistry metricRegistry,
                                       List<File> source,
                                       Supplier<Z> init,
                                       BiFunction<Z, String, Z> merge,
                                       int numThreads, int queueSize, int batchSize) {
        this.metricRegistry = metricRegistry;
        this.queueSize = queueSize;
        this.batchSize = batchSize;
        this.numThread = numThreads;
        this.source = source;
        this.init = init;
        this.merge = merge;
    }

    @Override
    protected Z doCall() {
        CpuIntensiveAggregator<Z, String> processor = new CpuIntensiveAggregator<>(metricRegistry, init, merge, numThread, queueSize, batchSize);
        try (Stream<String> source = readData(this.source)) {
            return processor.aggregate(source);
        }
    }
}
