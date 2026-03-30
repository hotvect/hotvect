package com.hotvect.onlineutils.concurrency.fileutils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.onlineutils.concurrency.CallerBlocksPolicy;
import com.hotvect.onlineutils.util.MetricUtils;
import com.hotvect.utils.VerboseRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

class UnorderedMultiFileReader<X> {
    private static final Logger log = LoggerFactory.getLogger(UnorderedMultiFileReader.class);
    private final List<File> files;
    private final ThreadPoolExecutor service;
    private List<Future<?>> handles;

    private final LongAdder lineCounter;
    private long startTime;
    private final ReadState<X> state;
    private final FileFormat fileFormat;

    public ReadState<X> getReadState() {
        return this.state;
    }


    static class ReadState<X> {
        private final BlockingQueue<X> readQueue;
        private volatile boolean readDone;
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        ReadState(BlockingQueue<X> readQueue) {
            this.readQueue = readQueue;
        }

        public BlockingQueue<X> getReadQueue() {
            return readQueue;
        }

        public boolean isReadDone() {
            return readDone;
        }

        public void setReadDone() {
            this.readDone = true;
        }

        public void reportError(Throwable e){
            Throwable root = Throwables.getRootCause(e);
            this.error.updateAndGet(existing -> {
                if (existing == null) {
                    return e;
                }
                Throwable existingRoot = Throwables.getRootCause(existing);
                boolean existingIsInterrupt = existingRoot instanceof InterruptedException;
                boolean newIsInterrupt = root instanceof InterruptedException;
                if (existingIsInterrupt && !newIsInterrupt) {
                    return e;  // allow a real error to replace an interrupt placeholder
                }
                return existing;  // otherwise keep the first error we saw
            });
        }

        public Throwable getError() {
            return error.get();
        }
    }

    public UnorderedMultiFileReader(int readQueueSize, List<File> files, int inputThreadNum){
        this.state = new ReadState<>(new LinkedBlockingQueue<>(readQueueSize));
        this.lineCounter = new LongAdder();

        this.service = new ThreadPoolExecutor(
                inputThreadNum,
                inputThreadNum,
                60,
                TimeUnit.HOURS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder().setNameFormat(this.getClass().getSimpleName() +"-input-%s").setUncaughtExceptionHandler(
                        (t, e) -> log.error("Uncaught error", e)
                ).build(),
                new CallerBlocksPolicy()
        );


        this.files = FileUtils.listFiles(files).collect(Collectors.toList());

        // Validate that all files have the same format
        if (!this.files.isEmpty()) {
            this.fileFormat = FileFormat.validateUniformFormat(this.files);
            log.info("Detected file format: {} for {} files", this.fileFormat, this.files.size());
        } else {
            this.fileFormat = null;
        }
    }

    public void start(){
        this.startTime = System.nanoTime();
        this.handles = new ArrayList<>(files.size());
        for (File file : files) {
            handles.add(service.submit(new VerboseRunnable() {
                @Override
                protected void doRun() throws Exception {
                    try(RecordReader<X> reader = RecordReader.create(file)){
                        while (reader.hasNext()) {
                            X record = reader.next();
                            lineCounter.increment();
                            state.getReadQueue().put(record);
                        }
                    }catch (Throwable e){
                        Throwable root = Throwables.getRootCause(e);
                        if(root instanceof InterruptedException){
                            log.warn("Reader was interrupted. Aborting.");
                        } else {
                            log.error("Error encountered during reading, aborting", e);
                        }
                        state.reportError(e);
                        state.setReadDone();
                        service.shutdownNow();
                        throw new RuntimeException(Throwables.getRootCause(e));
                    }
                }
            }));
        }
        service.shutdown();
    }

    public Map<String, Object> awaitTermination(){
        try {
            checkState(service.awaitTermination(10000, TimeUnit.DAYS));
            state.setReadDone();

            for (Future<?> handle : handles) {
                // Rethrows any exceptions encountered
                handle.get();
            }


            Map<String, Object> metadata = new HashMap<>();
            long linesRead = lineCounter.sum();
            metadata.put("lines_read", linesRead);
            
            // Calculate throughput rate
            double rate = MetricUtils.calculateRate(startTime, System.nanoTime(), linesRead);
            metadata.put("lines_read_at_rate", rate);
            metadata.put("number_of_files_read", this.files.size());
            return metadata;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(Throwables.getRootCause(e));
        }
    }

    public void abort() {
        this.service.shutdownNow();
    }
}
