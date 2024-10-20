package com.hotvect.offlineutils.util;

import com.codahale.metrics.Meter;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.offlineutils.concurrent.CallerBlocksPolicy;
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

    private final Meter meter;
    private final ReadState<X> state;

    // Theoretically we can ask the reader to perform conversion but for now this is unused
    private final Function<String, X> parseFun = null;

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
            this.error.compareAndSet(null, e);
        }

        public Throwable getError() {
            return error.get();
        }
    }

    public UnorderedMultiFileReader(int readQueueSize, Meter meter, List<File> files, int inputThreadNum){
        this.state = new ReadState<X>(new LinkedBlockingQueue<>(readQueueSize));
        this.meter = meter;

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
    }

    public void start(){
        this.handles = new ArrayList<>(files.size());
        for (File file : files) {
            handles.add(service.submit(new VerboseRunnable() {
                @Override
                protected void doRun() throws Exception {
                    try(BufferedReader br = FileUtils.toBufferedReader(file)){
                        for (;;) {
                            String line = br.readLine();
                            if (line == null){
                                break;
                            } else {
                                meter.mark();
                                state.getReadQueue().put((X)line);
                            }
                        }
                    }catch (Throwable e){
                        log.error("Error encountered during reading, aborting", e);
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
            metadata.put("lines_read", meter.getCount());
            metadata.put("lines_read_at_rate", meter.getMeanRate());
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
