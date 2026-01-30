package com.hotvect.onlineutils.concurrency;

import com.google.common.base.Throwables;
import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.utils.VerboseRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static com.google.common.base.Preconditions.checkState;

public class CpuIntensiveAggregator<Z, X> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CpuIntensiveAggregator.class);
    private static final LongConsumer NO_OP_METER = a -> {};
    private final LongConsumer recordMeter;
    private final Timer batchTimer;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final ThreadPoolExecutor cpuIntensiveExecutor;
    private final Z state;
    private final BiFunction<Z, X, Z> merger;
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    public CpuIntensiveAggregator(MeterRegistry meterRegistry,
                                  Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger) {
        this(meterRegistry,
                init,
                merger,
                -1,
                -1,
                -1);
    }

    public CpuIntensiveAggregator(Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger) {
        this(new SimpleMeterRegistry(),
                init,
                merger,
                -1,
                -1,
                -1);
    }

    public CpuIntensiveAggregator(MeterRegistry meterRegistry,
                                  Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger,
                                  int queueLength,
                                  int batchSize) {
        this(meterRegistry,
                init,
                merger,
                -1,
                queueLength,
                batchSize);
    }

    public CpuIntensiveAggregator(Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger,
                                  int queueLength,
                                  int batchSize) {
        this(new SimpleMeterRegistry(),
                init,
                merger,
                -1,
                queueLength,
                batchSize);
    }

    public CpuIntensiveAggregator(Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger,
                                  int numThreadsOpt,
                                  int queueLengthOpt,
                                  int batchSizeOpt) {
        this(new SimpleMeterRegistry(),
                init,
                merger,
                numThreadsOpt,
                queueLengthOpt,
                batchSizeOpt);
    }

    public CpuIntensiveAggregator(MeterRegistry meterRegistry,
                                  Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger,
                                  int numThreadsOpt,
                                  int queueLengthOpt,
                                  int batchSizeOpt) {
        this.meterRegistry = meterRegistry;
        this.batchTimer = Timer.builder("cpu.intensive.aggregator.batch")
                .description("Time taken to process batches")
                .register(meterRegistry);
        this.recordMeter = value -> meterRegistry.counter("cpu.intensive.aggregator.records",
                "description", "Number of records processed").increment(value);
        this.batchSize = ConcurrentUtils.getBatchSize(Optional.of(batchSizeOpt));
        int numThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(numThreadsOpt));
        int queueLength = ConcurrentUtils.getQueueLength(numThreads, Optional.of(queueLengthOpt));

        this.cpuIntensiveExecutor = new ThreadPoolExecutor(
                numThreads,
                numThreads,
                1, TimeUnit.DAYS,
                new LinkedBlockingQueue<>(queueLength),
                new ThreadFactoryBuilder().setNameFormat(CpuIntensiveAggregator.class + "-%s").build(),
                new CallerBlocksPolicy()
        );
        this.state = init.get();
        this.merger = merger;
    }

    public Z aggregate(Stream<X> input) {
        checkState(!this.cpuIntensiveExecutor.isShutdown(), "This aggregator is shutdown");
        UnmodifiableIterator<List<X>> batches = Iterators.partition(input.iterator(), batchSize);
        // Batches are submitted in order and thus the futures are sorted by order
        while (batches.hasNext()) {
            List<X> batch = batches.next();
            cpuIntensiveExecutor.submit(new ComputationTask(error, batch));
        }
        LOGGER.debug("Loading finished");

        this.cpuIntensiveExecutor.shutdown();
        try {
            checkState(this.cpuIntensiveExecutor.awaitTermination(Integer.MAX_VALUE, TimeUnit.MILLISECONDS));
            if (error.get() != null) {
                // We had encountered at least one error
                throw new IllegalStateException(Throwables.getRootCause(error.get()));
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return this.state;
    }

    private class ComputationTask extends VerboseRunnable {
        private final AtomicReference<Throwable> error;
        private final List<X> batch;

        private ComputationTask(AtomicReference<Throwable> error, List<X> batch) {
            this.error = error;
            this.batch = batch;
        }

        @Override
        protected void doRun() throws Exception {
            try {
                Timer.Sample sample = Timer.start(meterRegistry);
                batch.forEach(
                        x -> CpuIntensiveAggregator.this.merger.apply(CpuIntensiveAggregator.this.state, x)
                );
                sample.stop(batchTimer);
                recordMeter.accept(batch.size());
            } catch (Throwable e) {
                // Report error to our context
                error.set(e);
                throw new RuntimeException(e);
            }
        }
    }


}
