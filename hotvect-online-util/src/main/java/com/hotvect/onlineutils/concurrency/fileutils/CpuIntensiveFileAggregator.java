package com.hotvect.onlineutils.concurrency.fileutils;

import com.hotvect.onlineutils.concurrency.CpuIntensiveAggregator;
import io.micrometer.core.instrument.MeterRegistry;
import com.hotvect.utils.VerboseCallable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.hotvect.onlineutils.concurrency.fileutils.FileUtils.readData;

/**
 * CPU-intensive file aggregator that processes input files using an aggregation function,
 * utilizing multiple threads for reading and processing data.
 *
 * @param <S> the type of records read from the input files
 * @param <Z> the type of the aggregation result
 */
public class CpuIntensiveFileAggregator<S, Z> extends VerboseCallable<Z> {
    private final MeterRegistry meterRegistry;
    private final int numThread;
    private final int queueSize;
    private final int batchSize;
    private final List<File> source;
    private final Supplier<Z> init;
    private final BiFunction<Z, S, Z> merge;

    public static <S, Z> CpuIntensiveFileAggregator<S, Z> aggregator(MeterRegistry meterRegistry,
                                                                   List<File> source,
                                                                   Supplier<Z> init,
                                                                   BiFunction<Z, S, Z> merge,
                                                                   int numThreads, int queueSize, int batchSize) {
        return new CpuIntensiveFileAggregator<>(meterRegistry, source, init, merge, numThreads, queueSize, batchSize);
    }

    public static <S, Z> CpuIntensiveFileAggregator<S, Z> aggregator(MeterRegistry meterRegistry,
                                                                     List<File> source,
                                                                     Supplier<Z> init,
                                                                     BiFunction<Z, S, Z> merge) {
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
                                       BiFunction<Z, S, Z> merge,
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
        CpuIntensiveAggregator<Z, S> processor = new CpuIntensiveAggregator<>(meterRegistry, init, merge, numThread, queueSize, batchSize);
        try (Stream<S> source = readDataGeneric(this.source)) {
            return processor.aggregate(source);
        }
    }

    @SuppressWarnings("unchecked")
    private Stream<S> readDataGeneric(List<File> sources) {
        if (sources.isEmpty()) {
            return Stream.empty();
        }

        // Detect file format from the first file
        FileFormat fileFormat = FileFormat.detectFormat(sources.get(0));

        // Validate that all files have the same format
        FileFormat.validateUniformFormat(sources);

        switch (fileFormat) {
            case TEXT:
                // For text files, return String stream (cast to S)
                return (Stream<S>) readData(sources);
            case AVRO:
                // For Avro files, we need to create a proper GenericRecord stream
                return sources.stream()
                    .flatMap(file -> {
                        try {
                            RecordReader<S> reader = RecordReader.create(file);
                            // Read all records into a list to ensure proper resource cleanup
                            List<S> records = new ArrayList<>();
                            try (RecordReader<S> autoCloseReader = reader) {
                                while (autoCloseReader.hasNext()) {
                                    records.add(autoCloseReader.next());
                                }
                            }
                            return records.stream();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read Avro file: " + file, e);
                        }
                    });
            default:
                throw new IllegalArgumentException("Unsupported file format: " + fileFormat);
        }
    }
}
