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

/**
 * UnorderedFileMapper processes input files using a flatmap transformation function,
 * utilizing multiple threads for reading, processing, and writing data.
 */
public class UnorderedFileMapper extends VerboseCallable<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(UnorderedFileMapper.class);

    private final MeterRegistry meterRegistry;
    private final int nComputationThreads;
    private final int batchSize;
    private final List<File> source;
    private final File dest;
    private final Function<String, List<String>> flatmapTransformation;
    private final Integer readQueueSize;
    private final Integer writeQueueSize;
    private final Integer nReaderThreads;

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
        this.flatmapTransformation = builder.flatmapTransformation;
        this.nComputationThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(builder.nComputationThreads));
        this.readQueueSize = builder.readQueueSize;
        this.writeQueueSize = builder.writeQueueSize;
        this.nReaderThreads = builder.nReaderThreads;
    }

    /**
     * Static factory method to create an UnorderedFileMapper with specified parameters.
     *
     * @param meterRegistry     the meter registry
     * @param source             the list of source files
     * @param dest               the destination file
     * @param flatmapFunction    the flatmap transformation function
     * @param nThreads           the number of computation threads
     * @param batchSize          the batch size
     * @return an UnorderedFileMapper instance
     */
    public static UnorderedFileMapper mapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<String, List<String>> flatmapFunction, int nThreads, int batchSize) {
        return new UnorderedFileMapper(meterRegistry, source, dest, flatmapFunction, nThreads, batchSize);
    }

    /**
     * Static factory method to create an UnorderedFileMapper with default thread and batch configurations.
     *
     * @param meterRegistry     the meter registry
     * @param source             the list of source files
     * @param dest               the destination file
     * @param flatmapFunction    the flatmap transformation function
     * @return an UnorderedFileMapper instance
     */
    public static UnorderedFileMapper mapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<String, List<String>> flatmapFunction) {
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
     */
    private UnorderedFileMapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<String, List<String>> flatMapFunction, int nComputationThreads, int batchSize) {
        this.meterRegistry = meterRegistry;
        this.batchSize = ConcurrentUtils.getBatchSize(Optional.of(batchSize));
        this.source = source;
        this.dest = dest;
        this.flatmapTransformation = flatMapFunction;
        this.nComputationThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(nComputationThreads));
        this.readQueueSize = null;
        this.writeQueueSize = null;
        this.nReaderThreads = null;
    }

    /**
     * Creates a Builder for UnorderedFileMapper with required parameters.
     *
     * @param source             the list of source files
     * @param dest               the destination file
     * @param flatmapFunction    the flatmap transformation function
     * @return a Builder instance
     */
    public static Builder builder(List<File> source, File dest, Function<String, List<String>> flatmapFunction) {
        return new Builder(source, dest, flatmapFunction);
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        int actualReadQueueSize = (this.readQueueSize != null) ? this.readQueueSize : this.nComputationThreads * this.batchSize * 4;
        int actualWriteQueueSize = (this.writeQueueSize != null) ? this.writeQueueSize : actualReadQueueSize;
        int actualNReaderThreads = (this.nReaderThreads != null) ? this.nReaderThreads : max(1, (int) (this.nComputationThreads / 2.5));

        UnorderedMultiFileReader reader = new UnorderedMultiFileReader(
                actualReadQueueSize,
                this.source,
                actualNReaderThreads
        );

        MultiFileState<String, String> state = new MultiFileState<>(reader.getReadState(), actualWriteQueueSize);

        UnorderedCpuIntensiveMapper<String, String> processor = new UnorderedCpuIntensiveMapper<>(
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
                this.dest
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
                    log.error("Fail fast error checker detected an error. Aborting.", Throwables.getRootCause(err));
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
            this.error.set(e);
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

    /**
     * Builder class for UnorderedFileMapper.
     */
    public static class Builder {
        private final List<File> source;
        private final File dest;
        private final Function<String, List<String>> flatmapTransformation;
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

        /**
         * Builder constructor with required parameters.
         *
         * @param source                the list of source files
         * @param dest                  the destination file
         * @param flatmapTransformation the flatmap transformation function
         */
        public Builder(List<File> source, File dest, Function<String, List<String>> flatmapTransformation) {
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
        public Builder meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * Sets the number of computation threads.
         *
         * @param nThreads the number of computation threads
         * @return the Builder instance
         */
        public Builder nThreads(int nThreads) {
            this.nComputationThreads = nThreads;
            return this;
        }

        /**
         * Sets the batch size.
         *
         * @param batchSize the batch size
         * @return the Builder instance
         */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the read queue size.
         *
         * @param readQueueSize the read queue size
         * @return the Builder instance
         */
        public Builder readQueueSize(int readQueueSize) {
            this.readQueueSize = readQueueSize;
            return this;
        }

        /**
         * Sets the write queue size.
         *
         * @param writeQueueSize the write queue size
         * @return the Builder instance
         */
        public Builder writeQueueSize(int writeQueueSize) {
            this.writeQueueSize = writeQueueSize;
            return this;
        }

        /**
         * Sets the number of reader threads.
         *
         * @param readerThreads the number of reader threads
         * @return the Builder instance
         */
        public Builder readerThreads(int readerThreads) {
            this.nReaderThreads = readerThreads;
            return this;
        }

        /**
         * Builds and returns an UnorderedFileMapper instance.
         *
         * @return an UnorderedFileMapper instance
         */
        public UnorderedFileMapper build() {
            return new UnorderedFileMapper(this);
        }
    }
}