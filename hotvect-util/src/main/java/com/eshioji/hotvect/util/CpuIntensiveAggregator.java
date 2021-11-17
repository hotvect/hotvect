package com.eshioji.hotvect.util;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;

public class CpuIntensiveAggregator<Z, X> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CpuIntensiveAggregator.class);
    private final Timer batchTimer;
    private final Meter recordMeter;
    private final int batchSize;
    private final ThreadPoolExecutor cpuIntensiveExecutor;
    private final Z state;
    private final BiFunction<Z, X, Z> merger;

    public CpuIntensiveAggregator(MetricRegistry metricRegistry,
                                  Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger) {
        this(metricRegistry,
                init,
                merger,
                (Runtime.getRuntime().availableProcessors() > 1 ? Runtime.getRuntime().availableProcessors() - 1 : 1),
                (int) (Runtime.getRuntime().availableProcessors() * 1.5),
                5000);
    }

    public CpuIntensiveAggregator(MetricRegistry metricRegistry,
                                  Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger,
                                  int queueLength,
                                  int batchSize) {
        this(metricRegistry,
                init,
                merger,
                (Runtime.getRuntime().availableProcessors() > 1 ? Runtime.getRuntime().availableProcessors() - 1 : 1),
                queueLength,
                batchSize);
    }

    public CpuIntensiveAggregator(MetricRegistry metricRegistry,
                                  Supplier<Z> init,
                                  BiFunction<Z, X, Z> merger,
                                  int numThreads,
                                  int queueLength,
                                  int batchSize) {
        this.batchTimer = metricRegistry.timer(MetricRegistry.name(CpuIntensiveMapper.class, "batch"));
        this.recordMeter = metricRegistry.meter(MetricRegistry.name(CpuIntensiveMapper.class, "record"));
        this.batchSize = batchSize;

        this.cpuIntensiveExecutor = new ThreadPoolExecutor(
                numThreads,
                numThreads,
                1, TimeUnit.DAYS,
                new LinkedBlockingQueue<>(queueLength),
                new ThreadFactoryBuilder().setNameFormat(CpuIntensiveMapper.class + "-%s").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
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
            cpuIntensiveExecutor.submit(new ComputationTask(batch));
        }
        LOGGER.debug("Loading finished");

        this.cpuIntensiveExecutor.shutdown();
        try {
            checkState(this.cpuIntensiveExecutor.awaitTermination(Integer.MAX_VALUE, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return this.state;
    }

    private class ComputationTask extends VerboseRunnable {
        private final List<X> batch;

        private ComputationTask(List<X> batch) {
            this.batch = batch;
        }

        @Override
        protected void doRun() throws Exception {
            Timer.Context t = batchTimer.time();
            batch.forEach(
                    x -> CpuIntensiveAggregator.this.merger.apply(CpuIntensiveAggregator.this.state, x)
            );
            t.close();
            recordMeter.mark(batch.size());
        }
    }


}
