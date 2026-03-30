package com.hotvect.onlineutils.concurrency.fileutils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.onlineutils.util.MetricUtils;
import com.hotvect.utils.VerboseCallable;
import com.hotvect.utils.VerboseRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;

/**
 * Concurrent writer that consumes ByteBuffers from a shared queue and writes them to file shard(s).
 *
 * <p>Shard naming is fixed to {@code shard_%d<extension>} inside the destination directory. Caller
 * provides the destination directory and file extension; numberOfShards &lt;= 0 is clamped to 1.
 */
public class UnorderedFileWriter {
    private static final Logger log = LoggerFactory.getLogger(UnorderedFileWriter.class);
    private final LongAdder lineCounter;
    private final ExecutorService writer;
    private final int numberOfShards;
    private final File dest;
    private final String extension;

    private final AtomicReference<List<Future<?>>> handles = new AtomicReference<>();
    private final UnorderedFileMapper.MultiFileState<?, ByteBuffer> state;
    private volatile long startTime;

    /**
     * Creates a new UnorderedFileWriter with specified number of shards and fixed shard naming ({@code shard_%d<extension>}).
     *
     * @param state the shared state containing the write queue
     * @param dest the destination directory (will be created if missing)
     * @param extension file extension including leading dot (e.g., ".tfrecord")
     * @param numberOfShards number of output shards ({@code <=0} auto =&gt; 1, {@code >=1} explicit)
     */
    public UnorderedFileWriter(UnorderedFileMapper.MultiFileState<?, ByteBuffer> state, File dest, String extension, int numberOfShards) {
        if (extension == null) {
            throw new IllegalArgumentException("extension must not be null (e.g., .tfrecord, .tsv, .jsonl)");
        }
        this.numberOfShards = numberOfShards <= 0 ? 1 : numberOfShards;
        this.state = state;
        // If dest is a directory, use as-is; if it's a file, use parent as directory
        this.dest = dest;
        this.extension = extension;
        this.lineCounter = new LongAdder();
        this.writer = Executors.newFixedThreadPool(this.numberOfShards,
                new ThreadFactoryBuilder().setNameFormat(
                        UnorderedFileWriter.class.getSimpleName() + "-writer"
                ).build()
        );
        log.info("Created UnorderedFileWriter with {} shards", this.numberOfShards);
    }

    void start() {
        this.startTime = System.nanoTime();
        List<Future<?>> handles = new ArrayList<>();
        int actualShards = this.numberOfShards;
        for (int i = 0; i < actualShards; i++) {
            File shardDest = generateShardFilename(dest, extension, i);
            OutputStream out = newBufferedOutputStream(shardDest);
            WritableByteChannel channel = Channels.newChannel(out);

            Future<?> handle = this.writer.submit(new VerboseRunnable() {
                @Override
                protected void doRun() throws Exception {
                    try {
                        while (true) {
                            boolean isProcessingDone = state.isProcessingDone();
                            ByteBuffer line = state.getWriteQueue().poll(100, TimeUnit.MILLISECONDS);
                            if (line == null) {
                                // No new data to write, see if we are done
                                if (isProcessingDone) {
                                    // We are done
                                    out.close();
                                    return;
                                }
                            } else {
                                // Received data. Write
                                writeFully(channel, line);
                                lineCounter.increment();
                            }
                        }
                    } catch (Throwable e) {
                        state.setError(e);
                        throw new RuntimeException(Throwables.getRootCause(e));
                    }
                }
            });

            handles.add(handle);

        }
        checkState(this.handles.compareAndSet(null, handles), "Writer started more than once");
        this.writer.shutdown();
    }

    private static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private OutputStream newBufferedOutputStream(File shardDest) {
        try {
            // Create parent directories if they don't exist (required for directory-based sharding)
            File parentDir = shardDest.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    throw new IOException("Failed to create directory: " + parentDir);
                }
            }
            return new BufferedOutputStream(new FileOutputStream(shardDest), 128 << 10);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File generateShardFilename(File destDir, String extension, int shardId) {
        File dir = destDir;
        if (destDir.isFile()) {
            dir = destDir.getParentFile();
        }
        String fileName = String.format(Locale.ROOT, "shard_%d%s", shardId, extension);
        return dir == null ? new File(fileName) : new File(dir, fileName);
    }


    public Map<String, Object> awaitTermination() {
        try {
            checkState(this.writer.awaitTermination(10000, TimeUnit.DAYS));

            // Wait for all workers to complete
            List<Future<?>> futureList = this.handles.get();
            for (Future<?> future : futureList) {
                future.get();
            }

            // Calculate metrics once using shared counter
            Map<String, Object> metadata = new HashMap<>();
            long linesWritten = lineCounter.sum();
            metadata.put("lines_written", linesWritten);

            // Calculate throughput rate
            double rate = MetricUtils.calculateRate(startTime, System.nanoTime(), linesWritten);
            metadata.put("mean_rate_of_writing", rate);
            log.info("{} wrote {} lines at a mean rate of {}", UnorderedFileWriter.class.getSimpleName(), linesWritten, rate);

            return metadata;
        } catch (Exception e) {
            throw new RuntimeException(Throwables.getRootCause(e));
        }
    }


    public void abort() {
        this.writer.shutdownNow();
    }
}
