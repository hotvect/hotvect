package com.hotvect.offlineutils.util;

import com.codahale.metrics.Meter;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.utils.VerboseCallable;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;

class UnorderedFileWriter {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(UnorderedFileWriter.class);
    private final Meter meter;
    private final ExecutorService writer;

    private final OutputStream out;
    private final AtomicReference<Future<Map<String, Object>>> handle = new AtomicReference<>();
    private final UnorderedFileMapper.MultiFileState<?, String> state;

    public UnorderedFileWriter(UnorderedFileMapper.MultiFileState<?, String> state, Meter meter, File dest) throws IOException {
        this.state = state;
        this.meter = meter;
        this.out = new BufferedOutputStream(new FileOutputStream(dest), 128 << 10);
        this.writer = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder().setNameFormat(
                        UnorderedFileWriter.class.getSimpleName() + "-writer"
                ).build()
        );
    }

    void start() {
        Future<Map<String, Object>> handle = this.writer.submit(new VerboseCallable<>() {
            @Override
            protected Map<String, Object> doCall() throws Exception {
                try {
                    while (true) {
                        boolean isProcessingDone = state.isProcessingDone();
                        ByteBuffer line = state.getWriteQueue().poll(100, TimeUnit.MILLISECONDS);
                        if (line == null) {
                            // No new data to write, see if we are done
                            if (isProcessingDone) {
                                // We are done
                                out.close();
                                Map<String, Object> ret = new HashMap<>();
                                ret.put("mean_rate_of_writing", meter.getMeanRate());
                                ret.put("lines_written", meter.getCount());
                                log.info("{} wrote {} lines at a mean rate of {}", UnorderedFileWriter.class.getSimpleName(), meter.getCount(), meter.getMeanRate());
                                return ret;
                            }
                        } else {
                            // Received data. Write
                            Channels.newChannel(out).write(line);
                            meter.mark();
                        }
                    }
                } catch (Throwable e) {
                    state.setError(e);
                    throw new RuntimeException(Throwables.getRootCause(e));
                }
            }
        });
        checkState(this.handle.compareAndSet(null, handle), "Writer started more than once");
        this.writer.shutdown();
    }

    public Map<String, Object> awaitTermination() {
        try {
            checkState(this.writer.awaitTermination(10000, TimeUnit.DAYS));
            out.close();
            return this.handle.get().get();
        } catch (Exception e) {
            throw new RuntimeException(Throwables.getRootCause(e));
        }
    }


    public void abort() {
        this.writer.shutdownNow();
    }
}
