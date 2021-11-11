package com.eshioji.hotvect.util;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.anarres.parallelgzip.ParallelGZIPInputStream;
import org.anarres.parallelgzip.ParallelGZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static com.eshioji.hotvect.util.CpuIntensiveMapper.*;


public class CpuIntensiveFileMapper extends VerboseRunnable {
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

  public static CpuIntensiveFileMapper mapper(MetricRegistry metricRegistry, File source, File dest, Function<String, List<String>> flatmapFunction){
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

        ThreadPoolExecutor gzipWriters = getGzipWriters(3);


        try (var source = readData(this.source.toPath())) {
            var ext = Files.getFileExtension(dest.toPath().getFileName().toString());
            var isDestGzip = "gz".equalsIgnoreCase(ext);

            try (var file = new FileOutputStream(dest);
                 var sink = isDestGzip ? new ParallelGZIPOutputStream(file, gzipWriters) : file;
                 var writer = new BufferedWriter(new OutputStreamWriter(sink, Charsets.UTF_8), 65536)
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
        var queue = processor.start(source);
        metricRegistry.register(
                MetricRegistry.name(CpuIntensiveFileMapper.class, "queue", "size"),
                (Gauge<Integer>) queue::size);


        while (true) {
            var hadFinished = processor.hasLoadingFinished();
            var batch = queue.poll(1, TimeUnit.SECONDS);
            if (batch != null) {
                // will throw if batch was a failure
                for (List<String> result : batch.get()) {
                    if(result == null){
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
        var ext = Files.getFileExtension(source.getFileName().toString());
        var file = new FileInputStream(source.toFile());
        var spout = "gz".equalsIgnoreCase(ext) ? new GZIPInputStream(file) : file;
        var br = new BufferedReader(new InputStreamReader(spout, Charsets.UTF_8));
        return br.lines();
    }

}
