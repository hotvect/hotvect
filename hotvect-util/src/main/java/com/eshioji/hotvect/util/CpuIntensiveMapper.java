package com.eshioji.hotvect.util;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;

public class CpuIntensiveMapper<X, Y> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CpuIntensiveMapper.class);
    private final Timer batchTimer;
    private final Meter recordMeter;
    private final int queueLength;
    private final int batchSize;
    private final ThreadPoolExecutor cpuIntensiveExecutor;
    private final ExecutorService loader;
    private final Function<X, Y> mappingFun;

    public CpuIntensiveMapper(MetricRegistry metricRegistry, Function<X, Y> mappingFun){
        this(metricRegistry,
                mappingFun,
                (Runtime.getRuntime().availableProcessors() > 1 ? Runtime.getRuntime().availableProcessors() - 1 : 1),
                (int)(Runtime.getRuntime().availableProcessors() * 1.5),
                5000);
    }

    public CpuIntensiveMapper(MetricRegistry metricRegistry, Function<X, Y> mappingFun, int queueLength, int batchSize){
        this(metricRegistry,
                mappingFun,
                (Runtime.getRuntime().availableProcessors() > 1 ? Runtime.getRuntime().availableProcessors() - 1 : 1),
                queueLength,
                batchSize);
    }

    public CpuIntensiveMapper(MetricRegistry metricRegistry, Function<X, Y> mappingFun, int numThreads, int queueLength, int batchSize) {
        this.batchTimer = metricRegistry.timer(MetricRegistry.name(CpuIntensiveMapper.class, "batch"));
        this.recordMeter = metricRegistry.meter(MetricRegistry.name(CpuIntensiveMapper.class, "record"));
        this.queueLength = queueLength;
        this.batchSize = batchSize;

        this.loader = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder().setNameFormat(CpuIntensiveMapper.class + "loader-%s").build());

        this.cpuIntensiveExecutor = new ThreadPoolExecutor(
                numThreads,
                numThreads,
                1, TimeUnit.DAYS,
                new LinkedBlockingQueue<>(queueLength),
                new ThreadFactoryBuilder().setNameFormat(CpuIntensiveMapper.class + "-%s").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.mappingFun = mappingFun;

    }

    public BlockingQueue<Future<Collection<Y>>> start(Stream<X> input) {
        checkState(!this.loader.isShutdown(), "This processor is shutdown");
        var queue = new LinkedBlockingQueue<Future<Collection<Y>>>(queueLength);
        loader.submit(new LoadingTask(input, queue));
        this.loader.shutdown();
        return queue;
    }

    public boolean hasLoadingFinished() {
        return this.loader.isTerminated();
    }

    public void shutdown() {
        this.loader.shutdown();
        this.cpuIntensiveExecutor.shutdown();
    }

    public void shutdownNow() {
        this.loader.shutdownNow();
        this.cpuIntensiveExecutor.shutdownNow();
    }

    private class LoadingTask extends VerboseRunnable {
        private final Stream<X> input;
        private final BlockingQueue<Future<Collection<Y>>> queue;

        private LoadingTask(Stream<X> input, BlockingQueue<Future<Collection<Y>>> queue) {
            this.input = input;
            this.queue = queue;
        }

        @Override
        protected void doRun() throws Exception {
            var batches = Iterators.partition(input.iterator(), batchSize);
            // Batches are submitted in order and thus the futures are sorted by order
            while (batches.hasNext()) {
                var batch = batches.next();
                var batchFuture = cpuIntensiveExecutor.submit(new ComputationTask(batch));
                this.queue.put(batchFuture);
            }
            LOGGER.debug("Loading finished");
        }
    }

    private class ComputationTask extends VerboseCallable<Collection<Y>> {
        private final List<X> batch;

        private ComputationTask(List<X> batch) {
            this.batch = batch;
        }

        @Override
        public Collection<Y> doCall() {
            var t = batchTimer.time();
            var ys = batch.stream().map(mappingFun).collect(Collectors.toList());
            t.close();
            recordMeter.mark(batch.size());
            return ys;
        }
    }


}
