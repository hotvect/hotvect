package com.hotvect.onlineutils.concurrency.fileutils;

import com.hotvect.onlineutils.util.MetricUtils;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.LongAdder;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.onlineutils.concurrency.CpuIntensiveMapper;
import com.hotvect.onlineutils.concurrency.ConcurrentUtils;
import com.hotvect.utils.VerboseCallable;
import org.anarres.parallelgzip.ParallelGZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.hotvect.onlineutils.concurrency.fileutils.FileUtils.readData;


public class OrderedFileMapper extends VerboseCallable<Map<String, Object>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderedFileMapper.class);
    private final MeterRegistry meterRegistry;
    private final LongAdder recordCounter;
    private final Timer recordTimer;

    private final int nThreads;
    private final int queueSize;
    private final int batchSize;
    private final List<File> source;
    private final File dest;
    private final Function<String, List<String>> flatmapTransformation;

    private final int sample;


    public static OrderedFileMapper mapper(MeterRegistry meterRegistry,
                                           List<File> source,
                                           File dest,
                                           Function<String, List<String>> flatmapFunction,
                                           int nThreads,
                                           int queueLength,
                                           int batchSize) {
        return new OrderedFileMapper(meterRegistry,
                source,
                dest,
                flatmapFunction,
                nThreads,
                queueLength,
                batchSize,
                -1);
    }

    public static OrderedFileMapper mapper(MeterRegistry meterRegistry,
                                           List<File> source,
                                           File dest,
                                           Function<String, List<String>> flatmapFunction,
                                           int nThreads,
                                           int queueLength,
                                           int batchSize,
                                           int sample) {
        return new OrderedFileMapper(meterRegistry,
                source,
                dest,
                flatmapFunction,
                nThreads,
                queueLength,
                batchSize,
                sample);
    }

    public static OrderedFileMapper mapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<String, List<String>> flatmapFunction) {
        return mapper(meterRegistry, source, dest, flatmapFunction, -1, -1, -1);
    }

    public static OrderedFileMapper mapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<String, List<String>> flatmapFunction, int sample) {
        return mapper(meterRegistry, source, dest, flatmapFunction, -1, -1, -1, sample);
    }

    private OrderedFileMapper(MeterRegistry meterRegistry, List<File> source, File dest, Function<String, List<String>> flatMapFunction, int numThreads, int queueSize, int batchSize, int sample) {
        this.meterRegistry = meterRegistry;
        this.recordCounter = new LongAdder();
        this.recordTimer = Timer.builder("ordered.file.mapper.records")
                .description("Record processing timing")
                .register(meterRegistry);
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
        long startTime = System.nanoTime();

        AtomicInteger sampleCount =  this.sample <= 0 ? null : new AtomicInteger(sample);
        CpuIntensiveMapper<String, List<String>> processor = new CpuIntensiveMapper<>(meterRegistry, flatmapTransformation, nThreads, queueSize, batchSize);

        int gzipThreads = (int)Math.max(1, Math.round(this.nThreads * 0.2));

        ThreadPoolExecutor gzipWriters = getGzipWriters(gzipThreads);


        try (Stream<String> source = readData(this.source)) {
            //noinspection UnstableApiUsage
            String ext = Files.getFileExtension(dest.toPath().getFileName().toString());
            boolean isDestGzip = "gz".equalsIgnoreCase(ext);

            try (FileOutputStream file = new FileOutputStream(dest);
                 OutputStream sink = isDestGzip ? new ParallelGZIPOutputStream(file, gzipWriters) : file;
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), 128 << 10)
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
        metadata.put("total_record_count", recordCounter.sum());
        
        // Calculate throughput rate
        double rate = MetricUtils.calculateRate(startTime, System.nanoTime(), recordCounter.sum());
        metadata.put("mean_throughput", rate);
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
        Gauge.builder("ordered.file.mapper.queue.size", queue, q -> (double) q.size())
                .description("Queue size for ordered file mapper")
                .register(meterRegistry);


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
                                Timer.Sample sample = Timer.start();
                                writer.append(s);
                                writer.newLine();
                                this.recordCounter.increment();
                                sample.stop(this.recordTimer);
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
