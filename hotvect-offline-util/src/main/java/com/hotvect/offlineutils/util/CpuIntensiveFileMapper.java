package com.hotvect.offlineutils.util;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.hotvect.core.concurrent.CpuIntensiveMapper;
import com.hotvect.core.concurrent.VerboseRunnable;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.anarres.parallelgzip.ParallelGZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;


public class CpuIntensiveFileMapper extends VerboseRunnable {
    static final int DEFAULT_THREAD_NUM = (Runtime.getRuntime().availableProcessors() > 1 ? Runtime.getRuntime().availableProcessors() - 1 : 1);
    static final int DEFAULT_QUEUE_LENGTH = DEFAULT_THREAD_NUM * 2;
    static final int DEFAULT_BATCH_SIZE = 100;

    private static final Logger LOGGER = LoggerFactory.getLogger(CpuIntensiveFileMapper.class);
    private final MetricRegistry metricRegistry;
    private final Meter recordMeter;

    private final int nThreads;
    private final int queueSize;
    private final int batchSize;
    private final File source;
    private final File dest;
    private final Function<String, List<String>> flatmapTransformation;


    public static CpuIntensiveFileMapper mapper(MetricRegistry metricRegistry,
                                                File source,
                                                File dest,
                                                Function<String, List<String>> flatmapFunction,
                                                int nThreads,
                                                int queueLength,
                                                int batchSize) {
        return new CpuIntensiveFileMapper(metricRegistry,
                source,
                dest,
                flatmapFunction,
                nThreads,
                queueLength,
                batchSize);
    }

    public static CpuIntensiveFileMapper mapper(MetricRegistry metricRegistry, File source, File dest, Function<String, List<String>> flatmapFunction) {
        return mapper(metricRegistry, source, dest, flatmapFunction, DEFAULT_THREAD_NUM, DEFAULT_QUEUE_LENGTH, DEFAULT_BATCH_SIZE);
    }

    private CpuIntensiveFileMapper(MetricRegistry metricRegistry, File source, File dest, Function<String, List<String>> flatMapFunction, int numThreads, int queueSize, int batchSize) {
        this.metricRegistry = metricRegistry;
        this.recordMeter = metricRegistry.meter(MetricRegistry.name(CpuIntensiveFileMapper.class, "record"));
        this.queueSize = queueSize;
        this.batchSize = batchSize;
        this.source = source;
        this.dest = dest;
        this.flatmapTransformation = flatMapFunction;
        this.nThreads = numThreads;
    }

    @Override
    protected void doRun() {
        CpuIntensiveMapper<String, List<String>> processor = new CpuIntensiveMapper<>(metricRegistry, flatmapTransformation, nThreads, queueSize, batchSize);

        int gzipThreads = (Runtime.getRuntime().availableProcessors() / 2 > 1 ? Runtime.getRuntime().availableProcessors() / 2 - 1 : 1);

        ThreadPoolExecutor gzipWriters = getGzipWriters(gzipThreads);


        try (Stream<String> source = readData(this.source.toPath())) {
            String ext = Files.getFileExtension(dest.toPath().getFileName().toString());
            boolean isDestGzip = "gz".equalsIgnoreCase(ext);

            try (FileOutputStream file = new FileOutputStream(dest);
                 OutputStream sink = isDestGzip ? new ParallelGZIPOutputStream(file, gzipWriters) : file;
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sink, Charsets.UTF_8), 65536)
            ) {
                process(source, processor, writer);
            }
        } catch (Throwable e) {
            // Something bad happened
            LOGGER.error("Exception encountered", e);
            processor.shutdownNow();
            gzipWriters.shutdownNow();
        } finally {
            processor.shutdown();
            gzipWriters.shutdown();
        }
    }

    private ThreadPoolExecutor getGzipWriters(int nThreads) {
        return new ThreadPoolExecutor(
                nThreads,
                nThreads,
                1, TimeUnit.DAYS,
                new ArrayBlockingQueue<>(20 * nThreads),
                new ThreadFactoryBuilder().setNameFormat("gzip-writers-%s").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private void process(Stream<String> source, CpuIntensiveMapper<String, List<String>> processor, BufferedWriter writer) throws InterruptedException, java.util.concurrent.ExecutionException, IOException {
        BlockingQueue<Future<Collection<List<String>>>> queue = processor.start(source);
        metricRegistry.register(
                MetricRegistry.name(CpuIntensiveFileMapper.class, "queue", "size"),
                (Gauge<Integer>) queue::size);


        while (true) {
            boolean hadFinished = processor.hasLoadingFinished();
            Future<Collection<List<String>>> batch = queue.poll(1, TimeUnit.SECONDS);
            if (batch != null) {
                // will throw if batch was a failure
                for (List<String> result : batch.get()) {
                    if (result == null) {
                        throw new NullPointerException("result");
                    } else {
                        //TODO Add test for this path
                        // We had a flatmap function
                        for (String s : result) {
                            writer.append(s);
                            writer.newLine();
                            this.recordMeter.mark();
                        }
                    }
                }

            } else if (hadFinished) {
                // Last entry had been put on queue because loading had finished,
                // The only consumer (this thread) since queried the queue and it was empty (batch == null)
                // Means we are done
                break;
            }
        }
    }

    private static Stream<String> readData(Path source) throws IOException {
        String ext = Files.getFileExtension(source.getFileName().toString());
        FileInputStream file = new FileInputStream(source.toFile());
        InputStream spout = "gz".equalsIgnoreCase(ext) ? new GZIPInputStream(file) : file;
        BufferedReader br = new BufferedReader(new InputStreamReader(spout, Charsets.UTF_8));
        return br.lines();
    }

}
