package com.hotvect.offlineutils.util;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.offlineutils.concurrent.ConcurrentUtils;
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

public class UnorderedFileMapper extends VerboseCallable<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(UnorderedFileMapper.class);
    private final MetricRegistry metricRegistry;
    private final int nComputationThreads;
    private final int batchSize;
    private final List<File> source;
    private final File dest;
    private final Function<String, List<String>> flatmapTransformation;

    public static UnorderedFileMapper mapper(MetricRegistry metricRegistry,
                                             List<File> source,
                                             File dest,
                                             Function<String, List<String>> flatmapFunction,
                                             int nThreads,
                                             int batchSize) {
        return new UnorderedFileMapper(metricRegistry,
                source,
                dest,
                flatmapFunction,
                nThreads,
                batchSize);
    }

    public static UnorderedFileMapper mapper(MetricRegistry metricRegistry, List<File> source, File dest, Function<String, List<String>> flatmapFunction) {
        return mapper(metricRegistry, source, dest, flatmapFunction, -1, -1);
    }

    private UnorderedFileMapper(MetricRegistry metricRegistry, List<File> source, File dest, Function<String, List<String>> flatMapFunction, int nComputationThreads, int batchSize) {
        this.metricRegistry = metricRegistry;
        this.batchSize = ConcurrentUtils.getBatchSize(Optional.of(batchSize));
        this.source = source;
        this.dest = dest;
        this.flatmapTransformation = flatMapFunction;
        this.nComputationThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(nComputationThreads));
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        int readQueueSize = this.nComputationThreads * batchSize * 4;
        int writeQueueSize = readQueueSize;
        int nReaderThreads = max(1, (int)(this.nComputationThreads / 2.5));
        UnorderedMultiFileReader reader = new UnorderedMultiFileReader(
                readQueueSize,
                this.metricRegistry.meter(UnorderedMultiFileReader.class.getSimpleName() + "-read"),
                this.source,
                nReaderThreads
        );
        MultiFileState<String, String> state = new MultiFileState<>(reader.getReadState(), writeQueueSize);


        UnorderedCpuIntensiveMapper<String, String> processor = new UnorderedCpuIntensiveMapper<>(
                state,
                metricRegistry.meter(UnorderedFileMapper.class.getSimpleName() + "-processor"),
                this.flatmapTransformation,
                nComputationThreads,
                batchSize
        );

        UnorderedFileWriter writer = new UnorderedFileWriter(
                state,
                metricRegistry.meter(UnorderedFileWriter.class.getSimpleName() + "-writer"),
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
                if(err != null){
                    // Somewhere there was an error
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

        public void setError(Throwable e){
            this.error.set(e);
        }

        public Throwable getError(){
            Throwable processingError =  this.error.get();
            Throwable readError = this.readState.getError();

            // Assume read error is more interesting, given that it happens before processing
            if(readError != null){
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
}
