package com.hotvect.onlineutils.concurrency.fileutils;

import com.hotvect.onlineutils.util.MetricUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.LongAdder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.onlineutils.concurrency.CallerBlocksPolicy;
import com.hotvect.utils.VerboseCallable;
import com.hotvect.utils.VerboseRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

import static java.lang.Math.max;

public class FileReducer<Z> extends VerboseCallable<Z> {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileReducer.class);
    private final MeterRegistry meterRegistry;
    private final LongAdder recordCounter;
    private final Timer recordTimer;
    private final int numThread;
    private final int batchSize;
    private final Supplier<Z> init;
    private final BiFunction<Z, String, Z> accumulator;

    private final BinaryOperator<Z> reducer;

    private final ThreadPoolExecutor accumulators;
    private final ExecutorService reducers;
    private final UnorderedMultiFileReader<String> reader;
    private final BlockingQueue<Z> accumulatorQueue;

    private final AtomicReference<Throwable> error = new AtomicReference<>();



    private FileReducer(MeterRegistry meterRegistry, List<File> source, Supplier<Z> init, BiFunction<Z, String, Z> accumulator, BinaryOperator<Z> reducer, int numThreads, int batchSize) {
        this.meterRegistry = meterRegistry;
        this.recordCounter = new LongAdder();
        this.recordTimer = Timer.builder(FileReducer.class.getSimpleName() + ".processor")
                .description("Record processing timing by FileReducer")
                .register(meterRegistry);
        this.batchSize = batchSize;
        this.numThread = numThreads;
        this.init = init;
        this.accumulator = accumulator;
        this.reducer = reducer;

        this.accumulators = new ThreadPoolExecutor(
                numThreads,
                numThreads,
                1, TimeUnit.DAYS,
                // We don't really need a queue so 1 should do, but this doesn't hurt either
                new LinkedBlockingQueue<>(numThreads * 2),
                new ThreadFactoryBuilder().setNameFormat(FileReducer.class + "-accumulator-%s").build(),
                new CallerBlocksPolicy()
        );

        this.reducers = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder().setNameFormat(FileReducer.class + "-reducer-%s").build()
        );

        int readQueueSize = this.numThread * batchSize * 4;
        int nReaderThreads = max(1, (int) (this.numThread / 2.5));
        this.reader = new UnorderedMultiFileReader<>(
                readQueueSize,
                source,
                nReaderThreads
        );

        this.accumulatorQueue = new LinkedBlockingQueue<>(numThreads * 2);

    }


    public static <Z> FileReducer<Z> reducer(MeterRegistry metricRegistry,
                                             List<File> source,
                                             Supplier<Z> init,
                                             BiFunction<Z, String, Z> accumulator,
                                             BinaryOperator<Z> reducer,
                                             int numThreads,
                                             int batchSize
    ) {
        return new FileReducer<>(metricRegistry, source, init, accumulator, reducer, numThreads, batchSize);
    }

    public static <Z> FileReducer<Z> reducer(MeterRegistry metricRegistry,
                                             List<File> source,
                                             Supplier<Z> init,
                                             BiFunction<Z, String, Z> accumulator,
                                             BinaryOperator<Z> reducer,
                                             int batchSize
    ) {
        return new FileReducer<>(metricRegistry, source, init, accumulator, reducer, Math.max(Runtime.getRuntime().availableProcessors() - 1, 1), batchSize);
    }


    private class AccumulationTask extends VerboseRunnable {

        @Override
        protected void doRun() throws Exception {
            try {
                accumulate();
            } catch (Throwable e) {
                FileReducer.this.error.compareAndSet(null, e);
                reader.abort();
                accumulators.shutdownNow();
                reducers.shutdownNow();
                throw e;
            }
        }

        private void accumulate() throws InterruptedException {
            UnorderedMultiFileReader.ReadState<String> readState = FileReducer.this.reader.getReadState();

            while (true) {
                if (readState.getError() != null) {
                    FileReducer.this.error.set(readState.getError());
                    FileReducer.this.reader.abort();
                    throw new RuntimeException(readState.getError());
                } else if (Thread.currentThread().isInterrupted()) {
                    // Only set interrupted exception if there is no error right now, because interruption is
                    // the most uninteresting cause
                    InterruptedException ie = new InterruptedException();
                    FileReducer.this.error.compareAndSet(null, ie);
                    FileReducer.this.reader.abort();
                    throw ie;
                }

                boolean isReadDone = readState.isReadDone();

                List<String> read = new ArrayList<>(batchSize);
                String first = readState.getReadQueue().poll(300, TimeUnit.MILLISECONDS);
                if(first != null){
                    read.add(first);
                }
                readState.getReadQueue().drainTo(read, batchSize);
                if(!read.isEmpty()){
                    Timer.Sample sample = Timer.start();
                    Z acc = FileReducer.this.init.get();
                    for (String s : read) {
                        acc = accumulator.apply(acc, s);
                        FileReducer.this.recordCounter.increment();
                    }
                    FileReducer.this.accumulatorQueue.put(acc);
                    sample.stop(FileReducer.this.recordTimer);
                }

                if(isReadDone && readState.getReadQueue().isEmpty()){
                    // Read was done and then we drained the queue, so we are done
                    break;
                }
            }
        }
    }

    private class ReducingTask extends VerboseCallable<Z> {

        @Override
        protected Z doCall() throws Exception {
            try {
                return reduce();
            } catch (Throwable e) {
                FileReducer.this.error.compareAndSet(null, e);
                reader.abort();
                accumulators.shutdownNow();
                reducers.shutdownNow();
                throw e;
            }
        }

        private Z reduce() throws InterruptedException {
            Z reduced = FileReducer.this.init.get();
            while (true) {
                if (FileReducer.this.error.get() != null){
                    throw new RuntimeException(FileReducer.this.error.get());
                } else if (Thread.currentThread().isInterrupted()){
                    // Only set interrupted exception if there is no error right now, because interruption is
                    // the most uninteresting cause
                    InterruptedException ie = new InterruptedException();
                    FileReducer.this.error.compareAndSet(null, ie);
                    throw ie;
                }
                boolean isAccumulatorDone = accumulators.isTerminated();

                Z acc = FileReducer.this.accumulatorQueue.poll(300, TimeUnit.MILLISECONDS);
                if (acc != null) {
                    reduced = reducer.apply(reduced, acc);
                }
                if (isAccumulatorDone && FileReducer.this.accumulatorQueue.isEmpty()) {
                    // Accumulator was done and then we drained the queue, so we are done
                    return reduced;
                }
            }
        }
    }


    @Override
    protected Z doCall() {
        long startTime = System.nanoTime();

        reader.start();
        for (int i = 0; i < numThread; i++) {
            accumulators.submit(new AccumulationTask());
        }
        var handle = reducers.submit(new ReducingTask());
        reducers.shutdown();
        accumulators.shutdown();
        Map<String, Object> readerMetadata = reader.awaitTermination();
        LOGGER.info("Reader for file reducer finished reading {} records in {} seconds, {}/sec", readerMetadata.get("lines_read"), readerMetadata.get("elapsed_sec"), readerMetadata.get("lines_read_at_rate"));
        
        
        try {
            Z result = handle.get();
            
            // Calculate and log processing metrics
            double rate = MetricUtils.calculateRate(startTime, System.nanoTime(), recordCounter.sum());
            LOGGER.info("FileReducer processed {} records at {} records/sec", recordCounter.sum(), rate);
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}