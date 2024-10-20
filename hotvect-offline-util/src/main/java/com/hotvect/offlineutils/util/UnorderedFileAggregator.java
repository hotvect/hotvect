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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static java.lang.Math.max;

public class UnorderedFileAggregator<Z> extends VerboseCallable<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(UnorderedFileAggregator.class);

    private final MetricRegistry metricRegistry;
    private final int nComputationThreads;
    private final int batchSize;
    private final List<File> source;
    private final Z state;
    private final BiConsumer<Z, String> update;

    public static <Z> UnorderedFileAggregator<Z> aggregator(MetricRegistry metricRegistry, List<File> source, Z state, BiConsumer<Z, String> update, int nThreads, int batchSize) {
        return new UnorderedFileAggregator<>(metricRegistry, source, state, update, nThreads, batchSize);
    }

    public static <Z> UnorderedFileAggregator<Z> aggregator(MetricRegistry metricRegistry, List<File> source, Z state, BiConsumer<Z, String> update) {
        return aggregator(metricRegistry, source, state, update, -1, -1);
    }

    private UnorderedFileAggregator(MetricRegistry metricRegistry, List<File> source, Z state, BiConsumer<Z, String> update, int nComputationThreads, int batchSize) {
        this.metricRegistry = metricRegistry;
        this.batchSize = ConcurrentUtils.getBatchSize(Optional.of(batchSize));
        this.source = source;
        this.state = state;
        this.update = update;
        this.nComputationThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(nComputationThreads));
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        int readQueueSize = this.nComputationThreads * batchSize * 4;
        int nReaderThreads = max(1, (int)(this.nComputationThreads / 2.5));

        UnorderedMultiFileReader<String> reader = new UnorderedMultiFileReader<>(
                readQueueSize,
                this.metricRegistry.meter(UnorderedMultiFileReader.class.getSimpleName() + "-read"),
                this.source,
                nReaderThreads
        );

        MultiFileState multiFileState = new MultiFileState(reader.getReadState());

        UnorderedCpuIntensiveAggregator<Z> processor = new UnorderedCpuIntensiveAggregator<>(
                multiFileState,
                metricRegistry.meter(UnorderedFileAggregator.class.getSimpleName() + "-processor"),
                this.update,
                nComputationThreads,
                batchSize,
                this.state
        );

        long start = System.nanoTime();

        reader.start();
        processor.start();

        ScheduledExecutorService errorChecker = MoreExecutors.getExitingScheduledExecutorService(
                new ScheduledThreadPoolExecutor(
                        1,
                        new ThreadFactoryBuilder().setNameFormat("fail-fast-error-checker").build()
                )
        );

        errorChecker.scheduleAtFixedRate(new VerboseRunnable() {
            @Override
            protected void doRun() throws Exception {
                Throwable err = multiFileState.getError();
                if(err != null){
                    // Somewhere there was an error
                    log.error("Fail fast error checker detected an error. Aborting.", Throwables.getRootCause(err));
                    reader.abort();
                    processor.abort();
                    multiFileState.setReadDone();
                    multiFileState.setProcessingDone();
                    throw new RuntimeException("Fail fast", Throwables.getRootCause(err));
                }
            }
        }, 2, 2, TimeUnit.SECONDS);

        Map<String, Object> metadata = new HashMap<>();
        metadata.putAll(reader.awaitTermination());
        metadata.putAll(processor.awaitTermination());

        long end = System.nanoTime();
        long elapsed = end - start;
        long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(elapsed);

        log.info("Aggregation completed in {} seconds", elapsedSec);
        return metadata;
    }

    static class MultiFileState {
        private final UnorderedMultiFileReader.ReadState<String> readState;
        private volatile boolean processingDone;
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        MultiFileState(UnorderedMultiFileReader.ReadState<String> readState) {
            this.readState = readState;
        }

        public void setError(Throwable e){
            this.error.set(e);
        }

        public Throwable getError(){
            Throwable processingError = this.error.get();
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

        public BlockingQueue<String> getReadQueue() {
            return this.readState.getReadQueue();
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