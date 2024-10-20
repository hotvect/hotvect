package com.hotvect.offlineutils.util;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.offlineutils.concurrent.ConcurrentUtils;
import com.hotvect.offlineutils.concurrent.CpuIntensiveMapper;
import com.hotvect.utils.VerboseCallable;
import org.anarres.parallelgzip.ParallelGZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.hotvect.offlineutils.util.FileUtils.readData;


public class OrderedFileMapper extends VerboseCallable<Map<String, Object>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderedFileMapper.class);
    private final MetricRegistry metricRegistry;
    private final Meter recordMeter;

    private final int nThreads;
    private final int queueSize;
    private final int batchSize;
    private final List<File> source;
    private final File dest;
    private final Function<String, List<String>> flatmapTransformation;

    private final int sample;


    public static OrderedFileMapper mapper(MetricRegistry metricRegistry,
                                           List<File> source,
                                           File dest,
                                           Function<String, List<String>> flatmapFunction,
                                           int nThreads,
                                           int queueLength,
                                           int batchSize) {
        return new OrderedFileMapper(metricRegistry,
                source,
                dest,
                flatmapFunction,
                nThreads,
                queueLength,
                batchSize,
                -1);
    }

    public static OrderedFileMapper mapper(MetricRegistry metricRegistry,
                                           List<File> source,
                                           File dest,
                                           Function<String, List<String>> flatmapFunction,
                                           int nThreads,
                                           int queueLength,
                                           int batchSize,
                                           int sample) {
        return new OrderedFileMapper(metricRegistry,
                source,
                dest,
                flatmapFunction,
                nThreads,
                queueLength,
                batchSize,
                sample);
    }

    public static OrderedFileMapper mapper(MetricRegistry metricRegistry, List<File> source, File dest, Function<String, List<String>> flatmapFunction) {
        return mapper(metricRegistry, source, dest, flatmapFunction, -1, -1, -1);
    }

    public static OrderedFileMapper mapper(MetricRegistry metricRegistry, List<File> source, File dest, Function<String, List<String>> flatmapFunction, int sample) {
        return mapper(metricRegistry, source, dest, flatmapFunction, -1, -1, -1, sample);
    }

    private OrderedFileMapper(MetricRegistry metricRegistry, List<File> source, File dest, Function<String, List<String>> flatMapFunction, int numThreads, int queueSize, int batchSize, int sample) {
        this.metricRegistry = metricRegistry;
        this.recordMeter = metricRegistry.meter(MetricRegistry.name(OrderedFileMapper.class, "record"));
        this.nThreads = ConcurrentUtils.getThreadNumForCpuBoundTasks(Optional.of(numThreads));
        this.batchSize = ConcurrentUtils.getBatchSize(Optional.of(batchSize));
        this.queueSize = ConcurrentUtils.getQueueLength(numThreads, Optional.of(queueSize));
        this.source = source;
        this.dest = dest;
        this.flatmapTransformation = flatMapFunction;
        this.sample = sample;
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        AtomicInteger sampleCount =  this.sample <= 0 ? null : new AtomicInteger(sample);
        CpuIntensiveMapper<String, List<String>> processor = new CpuIntensiveMapper<>(metricRegistry, flatmapTransformation, nThreads, queueSize, batchSize);

        int gzipThreads = (int)Math.max(1, Math.round(this.nThreads * 0.2));

        ThreadPoolExecutor gzipWriters = getGzipWriters(gzipThreads);


        try (Stream<String> source = readData(this.source)) {
            //noinspection UnstableApiUsage
            String ext = Files.getFileExtension(dest.toPath().getFileName().toString());
            boolean isDestGzip = "gz".equalsIgnoreCase(ext);

            try (FileOutputStream file = new FileOutputStream(dest);
                 OutputStream sink = isDestGzip ? new ParallelGZIPOutputStream(file, gzipWriters) : file;
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sink, Charsets.UTF_8), 65536)
            ) {
                process(source, processor, writer, sampleCount);
            }
        } catch (Throwable e) {
            // Something bad happened
            LOGGER.error("Exception encountered", e);
            processor.shutdownNow();
            gzipWriters.shutdownNow();
            throw e;
        } finally {
            processor.shutdown();
            gzipWriters.shutdown();
        }

        processor.awaitTermination();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("mean_throughput", recordMeter.getMeanRate());
        metadata.put("total_record_count", recordMeter.getCount());
        return metadata;
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

    private void process(Stream<String> source, CpuIntensiveMapper<String, List<String>> processor, BufferedWriter writer, AtomicInteger sampleCount) throws InterruptedException, ExecutionException, IOException {
        BlockingQueue<Future<Collection<List<String>>>> queue = processor.start(source);
        metricRegistry.register(
                MetricRegistry.name(OrderedFileMapper.class, "queue", "size"),
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
                            if(sampleCount == null || sampleCount.getAndDecrement() > 0) {
                                writer.append(s);
                                writer.newLine();
                                this.recordMeter.mark();
                            } else {
                                // We wrote enough samples
                                LOGGER.debug("Wrote number of samples asked, shutting down");
                                processor.shutdownNow();
                                return;
                            }
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

}
