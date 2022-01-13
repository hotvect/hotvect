package com.eshioji.hotvect.offlineutils.util;

import com.codahale.metrics.MetricRegistry;
import com.hotvect.core.concurrent.CpuIntensiveAggregator;
import com.hotvect.core.concurrent.VerboseCallable;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class CpuIntensiveFileAggregator<Z> extends VerboseCallable<Z> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CpuIntensiveFileAggregator.class);
    private final MetricRegistry metricRegistry;
    private final int numThread;
    private final int queueSize;
    private final int batchSize;
    private final File source;
    private final Supplier<Z> init;
    private final BiFunction<Z, String, Z> merge;

    public static <Z> CpuIntensiveFileAggregator<Z> aggregator(MetricRegistry metricRegistry,
                                                               File source,
                                                               Supplier<Z> init,
                                                               BiFunction<Z, String, Z> merge,
                                                               int numThreads, int queueSize, int batchSize) {
        return new CpuIntensiveFileAggregator<>(metricRegistry, source, init, merge, numThreads, queueSize, batchSize);
    }

    public static <Z> CpuIntensiveFileAggregator<Z> aggregator(MetricRegistry metricRegistry,
                                                               File source,
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
                                       File source,
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
    protected Z doCall() throws Exception {
        CpuIntensiveAggregator<Z, String> processor = new CpuIntensiveAggregator<>(metricRegistry, init, merge, numThread, queueSize, batchSize);
        try (Stream<String> source = readData(this.source.toPath())) {
            return processor.aggregate(source);
        }
    }

    private static Stream<String> readData(Path source) throws IOException {
        String ext = Files.getFileExtension(source.getFileName().toString());
        FileInputStream file = new FileInputStream(source.toFile());
        InputStream spout = "gz".equals(ext.toLowerCase()) ? new GZIPInputStream(file) : file;
        BufferedReader br = new BufferedReader(new InputStreamReader(spout, Charsets.UTF_8));
        return br.lines();
    }

}
