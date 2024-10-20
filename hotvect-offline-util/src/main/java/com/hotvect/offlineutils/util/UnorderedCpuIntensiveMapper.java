package com.hotvect.offlineutils.util;

import com.codahale.metrics.Meter;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.offlineutils.concurrent.CallerBlocksPolicy;
import com.hotvect.utils.VerboseCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;

class UnorderedCpuIntensiveMapper<X, Y> {
    private static final Logger log = LoggerFactory.getLogger(com.hotvect.offlineutils.concurrent.CpuIntensiveMapper.class);
    private List<Future<?>> handles;
    private final Meter batchMeter;
    private final int batchSize;
    private final ThreadPoolExecutor cpuIntensiveExecutor;
    private final Function<X, List<Y>> mappingFun;

    private final Meter meter;

    private final UnorderedFileMapper.MultiFileState<X, Y> state;

    public UnorderedCpuIntensiveMapper(UnorderedFileMapper.MultiFileState<X, Y> state, Meter meter, Function<X, List<Y>> mappingFun, int numThreads, int batchSize) {
        this.state = state;
        this.meter = meter;
        this.batchSize = batchSize;

        this.cpuIntensiveExecutor = new ThreadPoolExecutor(
                numThreads,
                numThreads,
                1, TimeUnit.DAYS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder().setNameFormat(UnorderedCpuIntensiveMapper.class + "-%s").build(),
                new CallerBlocksPolicy()
        );
        this.mappingFun = mappingFun;
        this.batchMeter = new Meter();

    }

    public void start() {
        List<Future<?>> handles = new ArrayList<>();
        for (int i = 0; i < this.cpuIntensiveExecutor.getCorePoolSize(); i++) {
            handles.add(cpuIntensiveExecutor.submit(new UnorderedCpuIntensiveMapper<X, Y>.ComputationTask()));
        }
        this.handles = handles;
        this.cpuIntensiveExecutor.shutdown();
    }

    public Map<String, Object> awaitTermination() throws Exception {
        Map<String, Object> metadata = new HashMap<>();
        checkState(this.cpuIntensiveExecutor.awaitTermination(10000, TimeUnit.DAYS));
        this.state.setProcessingDone();
        for (Future<?> handle : this.handles) {
            handle.get();
        }
        metadata.put("records_processed", meter.getCount());
        metadata.put("records_processed_at_rate", meter.getMeanRate());
        return metadata;
    }

    public void abort() {
        this.cpuIntensiveExecutor.shutdownNow();
    }

    private class ComputationTask extends VerboseCallable<Void> {

        @Override
        public Void doCall() {
            while (true) {
                try {
                    batchMeter.mark();
                    // Make sure you check for done BEFORE you poll the queue
                    var readDone = UnorderedCpuIntensiveMapper.this.state.isReadDone();
                    List<X> batch = new ArrayList<>(UnorderedCpuIntensiveMapper.this.batchSize);
                    UnorderedCpuIntensiveMapper.this.state.getReadQueue().drainTo(batch, UnorderedCpuIntensiveMapper.this.batchSize);
                    if (batch.isEmpty() && readDone) {
                        // No more data to process
                        UnorderedCpuIntensiveMapper.this.state.setProcessingDone();
                        log.debug("Processor task has no more data to process. Terminating. Data processed so far:{}", UnorderedCpuIntensiveMapper.this.meter.getCount());
                        return null;
                    } else if(batch.isEmpty()){
                        continue;
                    } else {
                        List<Y> ys = flatMap(batch, mappingFun);
                        for (Y y : ys) {
                            String s = y + "\n";
                            UnorderedCpuIntensiveMapper.this.state.getWriteQueue().put(StandardCharsets.UTF_8.encode(s));
                        }
                    }
                } catch (Throwable e) {
                    log.error("Encountered error during processing. Aborting.", e);
                    UnorderedCpuIntensiveMapper.this.state.setError(e);
                    UnorderedCpuIntensiveMapper.this.state.setProcessingDone();
                    throw new RuntimeException(Throwables.getRootCause(e));
                }
            }
        }

        private <S, T> List<T> flatMap(List<S> source, Function<S, List<T>> transformation) {
            if (source.isEmpty()) {
                return Collections.emptyList();
            } else if (source.size() == 1) {
                return transformation.apply(source.get(0));
            } else {
                List<T> ret = new ArrayList<>(source.size());
                for (S s : source) {
                    var transformed = transformation.apply(s);
                    ret.addAll(transformed);
                }
                return ret;
            }
        }

    }


}