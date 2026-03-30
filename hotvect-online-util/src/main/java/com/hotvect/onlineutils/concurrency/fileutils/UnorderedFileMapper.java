package com.hotvect.onlineutils.concurrency.fileutils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.micrometer.core.instrument.logging.LoggingRegistryConfig;
import java.time.Duration;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.onlineutils.concurrency.ConcurrentUtils;
import com.hotvect.utils.VerboseCallable;
import com.hotvect.utils.VerboseRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * UnorderedFileMapper processes input files using a flatmap transformation function,
 * utilizing multiple threads for reading, processing, and writing data.
 *
 * @param <T> the type of records read from the input files
 */
public class UnorderedFileMapper<T> extends VerboseCallable<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(UnorderedFileMapper.class);

    private final MeterRegistry meterRegistry;
    private final int nComputationThreads;
    private final int batchSize;
    private final List<File> source;
    private final File dest;
    private final String extension;
    private final Function<T, List<ByteBuffer>> flatmapTransformation;
    private final Integer readQueueSize;
    private final Integer writeQueueSize;
    private final Integer nReaderThreads;
    private final Integer numberOfShards;

    /**
     * Creates an UnorderedFileMapper using provided parameters from the Builder.
     *
     * @param builder the Builder instance containing configuration parameters
     */
    private UnorderedFileMapper(Builder builder) {
        this.meterRegistry = builder.meterRegistry;
        this.batchSize = ConcurrentUtils.getBatchSize(Optional.of(builder.batchSize));
        this.source = builder.source;
        this.dest = builder.dest;
        this.extension = builder.extension;
        this.flatmapTransformation = builder.flatmapTransformation;
        this.nComputationThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(builder.nComputationThreads));
        this.readQueueSize = builder.readQueueSize;
        this.writeQueueSize = builder.writeQueueSize;
        this.nReaderThreads = builder.nReaderThreads;
        this.numberOfShards = builder.numberOfShards;
    }

    /**
     * Static factory method to create an UnorderedFileMapper with specified parameters.
     *
     * @param <T>                the type of records read from the input files
     * @param meterRegistry     the meter registry
     * @param source             the list of source files
     * @param dest               the destination file
     * @param flatmapFunction    the flatmap transformation function
     * @param nThreads           the number of computation threads
     * @param batchSize          the batch size
     * @return an UnorderedFileMapper instance
     * @deprecated Use {@link #builder(List, File, Function)} instead. Legacy single-file mode is deprecated.
     */
    @Deprecated(forRemoval = true)
    public static <T> UnorderedFileMapper<T> mapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<T, List<ByteBuffer>> flatmapFunction, int nThreads, int batchSize) {
        return new UnorderedFileMapper<>(meterRegistry, source, dest, flatmapFunction, nThreads, batchSize);
    }

    /**
     * Static factory method to create an UnorderedFileMapper with default thread and batch configurations.
     *
     * @param <T>                the type of records read from the input files
     * @param meterRegistry     the meter registry
     * @param source             the list of source files
     * @param dest               the destination file
     * @param flatmapFunction    the flatmap transformation function
     * @return an UnorderedFileMapper instance
     * @deprecated Use {@link #builder(List, File, Function)} instead. Legacy single-file mode is deprecated.
     */
    @Deprecated(forRemoval = true)
    public static <T> UnorderedFileMapper<T> mapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<T, List<ByteBuffer>> flatmapFunction) {
        return mapper(meterRegistry, source, dest, flatmapFunction, -1, -1);
    }


    /**
     * Private constructor used by static factory methods.
     *
     * @param meterRegistry     the meter registry
     * @param source             the list of source files
     * @param dest               the destination file
     * @param flatMapFunction    the flatmap transformation function
     * @param nComputationThreads the number of computation threads
     * @param batchSize          the batch size
     * @deprecated Use {@link Builder} instead. Legacy single-file mode is deprecated.
     */
    @Deprecated(forRemoval = true)
    private UnorderedFileMapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<T, List<ByteBuffer>> flatMapFunction, int nComputationThreads, int batchSize) {
        this.meterRegistry = meterRegistry;
        this.batchSize = ConcurrentUtils.getBatchSize(Optional.of(batchSize));
        this.source = source;
        this.dest = dest;
        this.extension = null;
        this.flatmapTransformation = flatMapFunction;
        this.nComputationThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(nComputationThreads));
        this.readQueueSize = null;
        this.writeQueueSize = null;
        this.nReaderThreads = null;
        this.numberOfShards = null;
    }

    /**
     * Creates a Builder for UnorderedFileMapper with required parameters.
     *
     * @param <T>                the type of records read from the input files
     * @param source             the list of source files
     * @param dest               the destination file
     * @param flatmapFunction    the flatmap transformation function
     * @return a Builder instance
     */
    public static <T> Builder<T> builder(List<File> source, File dest, Function<T, List<ByteBuffer>> flatmapFunction) {
        return new Builder<>(source, dest, flatmapFunction);
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        int actualReadQueueSize = (this.readQueueSize != null) ? this.readQueueSize : this.nComputationThreads * this.batchSize * 4;
        int actualWriteQueueSize = (this.writeQueueSize != null) ? this.writeQueueSize : actualReadQueueSize;
        int actualNReaderThreads = (this.nReaderThreads != null) ? this.nReaderThreads : max(1, min(32, (int) (this.nComputationThreads / 1.5)));

        UnorderedMultiFileReader<T> reader = new UnorderedMultiFileReader<>(
                actualReadQueueSize,
                this.source,
                actualNReaderThreads
        );

        MultiFileState<T, ByteBuffer> state = new MultiFileState<>(reader.getReadState(), actualWriteQueueSize);

        UnorderedCpuIntensiveMapper<T, ByteBuffer> processor = new UnorderedCpuIntensiveMapper<>(
                state,
                Timer.builder("unordered.file.mapper.processor")
                        .description("Records processed by UnorderedFileMapper")
                        .register(this.meterRegistry),
                this.flatmapTransformation,
                nComputationThreads,
                batchSize
        );

        UnorderedFileWriter writer = new UnorderedFileWriter(
                state,
                this.dest,
                resolveExtension(this.dest, this.extension),
                this.numberOfShards != null ? this.numberOfShards : -1
        );

        long start = System.nanoTime();

        reader.start();
        processor.start();
        writer.start();

        ScheduledExecutorService errorChecker = MoreExecutors.getExitingScheduledExecutorService(
                new ScheduledThreadPoolExecutor(
                        1,
                        new ThreadFactoryBuilder().setNameFormat("fail-fast-error-checker").build()
                )
        );

        errorChecker.scheduleAtFixedRate(new VerboseRunnable() {
            @Override
            protected void doRun() throws Exception {
                Throwable err = state.getError();
                if (err != null) {
                    Throwable root = Throwables.getRootCause(err);
                    if(root instanceof InterruptedException){
                        log.info("Fail fast error checker detected interruptee exception");
                    } else {
                        log.error("Fail fast error checker detected an error. Aborting.", err);
                    }
                    reader.abort();
                    processor.abort();
                    writer.abort();
                    state.setReadDone();
                    state.setProcessingDone();
                    throw new RuntimeException("Fail fast", Throwables.getRootCause(err));
                }
            }
        }, 2, 2, TimeUnit.SECONDS);

        Map<String, Object> metadata = new HashMap<>();
        metadata.putAll(reader.awaitTermination());
        metadata.putAll(processor.awaitTermination());
        metadata.putAll(writer.awaitTermination());


        long end = System.nanoTime();
        long elapsed = end - start;
        long numRecords = (Long) metadata.get("lines_written");
        double recordsPerSec = 1.0 * TimeUnit.SECONDS.toNanos(numRecords) / elapsed;
        long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(elapsed);
        log.info("Writer finished writing {} records in {} seconds, {}/sec", numRecords, elapsedSec, recordsPerSec);
        return metadata;
    }

    static class MultiFileState<X, Y> {
        private final UnorderedMultiFileReader.ReadState<X> readState;
        private volatile boolean processingDone;
        private final BlockingQueue<ByteBuffer> writeQueue;
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        MultiFileState(UnorderedMultiFileReader.ReadState<X> readState, int writeQueueSize) {
            this.readState = readState;
            this.writeQueue = new LinkedBlockingQueue<>(writeQueueSize);
        }

        public void setError(Throwable e) {
            Throwable root = Throwables.getRootCause(e);
            this.error.updateAndGet(existing -> {
                if (existing == null) {
                    return e;
                }
                Throwable existingRoot = Throwables.getRootCause(existing);
                boolean existingIsInterrupt = existingRoot instanceof InterruptedException;
                boolean newIsInterrupt = root instanceof InterruptedException;
                if (existingIsInterrupt && !newIsInterrupt) {
                    return e;  // allow a real error to replace an interrupt placeholder
                }
                return existing;  // otherwise keep the first error we saw
            });
        }

        public Throwable getError() {
            Throwable processingError = this.error.get();
            Throwable readError = this.readState.getError();
            if (readError != null) {
                return readError;
            } else {
                return processingError;
            }
        }

        public boolean isReadDone() {
            return this.readState.isReadDone();
        }

        public BlockingQueue<X> getReadQueue() {
            return this.readState.getReadQueue();
        }

        public BlockingQueue<ByteBuffer> getWriteQueue() {
            return writeQueue;
        }

        public void setProcessingDone() {
            this.processingDone = true;
        }

        public boolean isProcessingDone() {
            return processingDone;
        }

        public void setReadDone() {
            this.readState.setReadDone();
        }
    }

    private static String resolveExtension(File dest, String providedExtension) {
        if (providedExtension != null) {
            return providedExtension;
        }
        String name = dest.getName();
        int idx = name.lastIndexOf('.');
        if (idx >= 0) {
            return name.substring(idx);
        }
        return "";
    }

    /**
     * Builder class for UnorderedFileMapper.
     *
     * @param <T> the type of records read from the input files
     */
    public static class Builder<T> {
        private final List<File> source;
        private final File dest;
        private String extension = null;
        private final Function<T, List<ByteBuffer>> flatmapTransformation;
        private MeterRegistry meterRegistry = LoggingMeterRegistry.builder(new LoggingRegistryConfig() {
            @Override
            public Duration step() {
                return Duration.ofSeconds(10);
            }
            @Override
            public String get(String key) {
                return null;
            }
        }).build();
        private int nComputationThreads = -1;
        private int batchSize = -1;
        private Integer readQueueSize = null;
        private Integer writeQueueSize = null;
        private Integer nReaderThreads = null;
        private Integer numberOfShards = null;

        /**
         * Builder constructor with required parameters.
         *
         * @param source                the list of source files
         * @param dest                  the destination file
         * @param flatmapTransformation the flatmap transformation function
         */
        public Builder(List<File> source, File dest, Function<T, List<ByteBuffer>> flatmapTransformation) {
            this.source = source;
            this.dest = dest;
            this.flatmapTransformation = flatmapTransformation;
        }

        /**
         * Sets the MeterRegistry.
         *
         * @param meterRegistry the meter registry
         * @return the Builder instance
         */
        public Builder<T> meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * Sets the number of computation threads.
         *
         * @param nThreads the number of computation threads
         * @return the Builder instance
         */
        public Builder<T> nThreads(int nThreads) {
            this.nComputationThreads = nThreads;
            return this;
        }

        /**
         * Sets the batch size.
         *
         * @param batchSize the batch size
         * @return the Builder instance
         */
        public Builder<T> batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the read queue size.
         *
         * @param readQueueSize the read queue size
         * @return the Builder instance
         */
        public Builder<T> readQueueSize(int readQueueSize) {
            this.readQueueSize = readQueueSize;
            return this;
        }

        /**
         * Sets the write queue size.
         *
         * @param writeQueueSize the write queue size
         * @return the Builder instance
         */
        public Builder<T> writeQueueSize(int writeQueueSize) {
            this.writeQueueSize = writeQueueSize;
            return this;
        }

        /**
         * Sets the number of reader threads.
         *
         * @param readerThreads the number of reader threads
         * @return the Builder instance
         */
        public Builder<T> readerThreads(int readerThreads) {
            this.nReaderThreads = readerThreads;
            return this;
        }

        /**
         * Sets the file extension for shard naming (e.g., ".tfrecord", ".tsv", ".jsonl").
         * When provided, UnorderedFileWriter will generate files as {@code shard_%d<extension>} inside the dest directory.
         */
        public Builder<T> extension(String extension) {
            this.extension = extension;
            return this;
        }

        /**
         * Sets the number of writer shards.
         * The destination filename must include a %d placeholder (e.g., "shard_%d.tfrecord").
         * <ul>
         *   <li>numberOfShards &lt;= 0: Auto-determine shard count (minimum 1)</li>
         *   <li>numberOfShards &gt;= 1: Explicit shard count</li>
         * </ul>
         *
         * @param numberOfShards the number of writer shards
         * @return the Builder instance
         */
        public Builder<T> numberOfShards(int numberOfShards) {
            this.numberOfShards = numberOfShards;
            return this;
        }

        /**
         * Builds and returns an UnorderedFileMapper instance.
         *
         * @return an UnorderedFileMapper instance
         */
        public UnorderedFileMapper<T> build() {
            return new UnorderedFileMapper<>(this);
        }
    }
}
