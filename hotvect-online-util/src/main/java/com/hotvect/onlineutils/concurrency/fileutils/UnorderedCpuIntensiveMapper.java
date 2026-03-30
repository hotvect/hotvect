package com.hotvect.onlineutils.concurrency.fileutils;

import com.hotvect.onlineutils.util.MetricUtils;
import io.micrometer.core.instrument.Timer;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.onlineutils.concurrency.CallerBlocksPolicy;
import com.hotvect.utils.VerboseCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;

class UnorderedCpuIntensiveMapper<X, Y> {
    private static final Logger log = LoggerFactory.getLogger(UnorderedCpuIntensiveMapper.class);
    private List<Future<?>> handles;
    private final int batchSize;
    private final ThreadPoolExecutor cpuIntensiveExecutor;
    private final Function<X, List<Y>> mappingFun;

    private final Timer timer;
    private final LongAdder recordCount;
    private long startTime;

    private final UnorderedFileMapper.MultiFileState<X, Y> state;

    public UnorderedCpuIntensiveMapper(UnorderedFileMapper.MultiFileState<X, Y> state, Timer timer, Function<X, List<Y>> mappingFun, int numThreads, int batchSize) {
        this.state = state;
        this.timer = timer;
        this.recordCount = new LongAdder();
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

    }

    public void start() {
        this.startTime = System.nanoTime();

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
        metadata.put("records_processed", recordCount.sum());
        
        // Calculate throughput rate
        double rate = MetricUtils.calculateRate(startTime, System.nanoTime(), recordCount.sum());
        metadata.put("records_processed_at_rate", rate);
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
                    // Make sure you check for done BEFORE you poll the queue
                    var readDone = UnorderedCpuIntensiveMapper.this.state.isReadDone();
                    List<X> batch = new ArrayList<>(UnorderedCpuIntensiveMapper.this.batchSize);
                    UnorderedCpuIntensiveMapper.this.state.getReadQueue().drainTo(batch, UnorderedCpuIntensiveMapper.this.batchSize);
                    if (batch.isEmpty() && readDone) {
                        // No more data to process
                        log.debug("Processor task has no more data to process. Terminating. Data processed so far:{}", UnorderedCpuIntensiveMapper.this.recordCount.sum());
                        return null;
                    } else if (batch.isEmpty()) {
                        continue;
                    } else {
                        Timer.Sample sample = Timer.start();
                        List<Y> ys = flatMap(batch, mappingFun);
                        for (Y y : ys) {
                            if(y instanceof ByteBuffer byteBuffer) {
                                UnorderedCpuIntensiveMapper.this.state.getWriteQueue().put(byteBuffer);
                                UnorderedCpuIntensiveMapper.this.recordCount.increment();
                            } else {
                                throw new IllegalStateException("Even though we have generic type Y, we actually need a ByteBuffer here. Got " + (y == null ? "null" : y.getClass()));
                            }
                        }
                        sample.stop(UnorderedCpuIntensiveMapper.this.timer);
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
