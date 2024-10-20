package com.hotvect.offlineutils.util;

import com.codahale.metrics.Meter;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.offlineutils.concurrent.CallerBlocksPolicy;
import com.hotvect.utils.VerboseCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkState;

class UnorderedCpuIntensiveAggregator<Z> {
    private static final Logger log = LoggerFactory.getLogger(UnorderedCpuIntensiveAggregator.class);
    private final UnorderedFileAggregator.MultiFileState multiFileState;
    private final Meter meter;
    private final BiConsumer<Z, String> update;
    private final Z sharedState;
    private final ThreadPoolExecutor cpuIntensiveExecutor;
    private final int batchSize;
    private final List<Future<Void>> computationTickets = new ArrayList<>();

    public UnorderedCpuIntensiveAggregator(UnorderedFileAggregator.MultiFileState multiFileState, Meter meter, BiConsumer<Z, String> update, int numThreads, int batchSize, Z sharedState) {
        this.multiFileState = multiFileState;
        this.meter = meter;
        this.update = update;
        this.sharedState = sharedState;
        this.batchSize = batchSize;
        this.cpuIntensiveExecutor = new ThreadPoolExecutor(
                numThreads, numThreads,
                1, TimeUnit.DAYS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder().setNameFormat(UnorderedCpuIntensiveAggregator.class.getSimpleName() + "-%d").build(),
                new CallerBlocksPolicy()
        );
    }

    public void start() {
        if (!computationTickets.isEmpty()) {
            throw new IllegalStateException("This aggregator has already been started");
        }
        for (int i = 0; i < this.cpuIntensiveExecutor.getCorePoolSize(); i++) {
            Future<Void> future = cpuIntensiveExecutor.submit(new ComputationTask());
            computationTickets.add(future);
        }
        this.cpuIntensiveExecutor.shutdown();
    }

    public Map<String, Object> awaitTermination() throws Exception {
        checkState(this.cpuIntensiveExecutor.awaitTermination(10000, TimeUnit.DAYS));
        this.multiFileState.setProcessingDone();
        // This rethrows any exceptions encountered during processing
        for (Future<Void> computationTicket : computationTickets) {
            computationTicket.get();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("records_processed", meter.getCount());
        metadata.put("records_processed_at_rate", meter.getMeanRate());
        return metadata;
    }

    public void abort() {
        this.cpuIntensiveExecutor.shutdownNow();
    }

    private class ComputationTask extends VerboseCallable<Void> {
        @Override
        protected Void doCall() {
            while (true) {
                try {
                    boolean readDone = multiFileState.isReadDone();
                    List<String> batch = new ArrayList<>(batchSize);
                    multiFileState.getReadQueue().drainTo(batch, batchSize);
                    if (batch.isEmpty() && readDone) {
                        log.debug("Processor task has no more data to process. Terminating. Data processed so far:{}", meter.getCount());
                        return null;
                    } else if (batch.isEmpty()) {
                        // Force yield
                        Thread.sleep(1);
                    } else {
                        for (String s : batch) {
                            update.accept(sharedState, s);
                            meter.mark();
                        }
                    }
                } catch (Throwable e) {
                    log.error("Encountered error during processing. Aborting.", e);
                    multiFileState.setError(e);
                    multiFileState.setProcessingDone();
                    throw new RuntimeException(Throwables.getRootCause(e));
                }
            }
        }
    }
}
