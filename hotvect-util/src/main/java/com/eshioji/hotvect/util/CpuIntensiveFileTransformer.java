package com.eshioji.hotvect.util;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.anarres.parallelgzip.ParallelGZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;


public class CpuIntensiveFileTransformer extends VerboseRunnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CpuIntensiveFileTransformer.class);
    private final MetricRegistry metricRegistry;
    private final int queueSize;
    private final int batchSize;
    private final File source;
    private final File dest;
    private final Function<String, String> transformation;

    public CpuIntensiveFileTransformer(MetricRegistry metricRegistry, File source, File dest, Function<String, String> transformation, int queueSize, int batchSize) {
        this.metricRegistry = metricRegistry;
        this.queueSize = queueSize;
        this.batchSize = batchSize;
        this.source = source;
        this.dest = dest;
        this.transformation = transformation;
    }

    @Override
    protected void doRun() {
        var processor = new CpuIntensiveProcessor<>(metricRegistry, transformation, queueSize, batchSize);
        try (var source = readData(this.source.toPath())) {
            var queue = processor.start(source);
            metricRegistry.register(
                    MetricRegistry.name(CpuIntensiveFileTransformer.class, "queue", "size"),
                    (Gauge<Integer>) queue::size);

            var ext = Files.getFileExtension(dest.toPath().getFileName().toString());
            var isDestGzip = "gz".equals(ext.toLowerCase());

            try (var file = new FileOutputStream(dest);
                 var sink = isDestGzip ? new ParallelGZIPOutputStream(file, 2) : file;
                 var writer = new BufferedWriter(new OutputStreamWriter(sink, Charsets.UTF_8), 65536)
            ) {
                process(processor, queue, writer);
            }
        } catch (Throwable e) {
            // Something bad happened
            LOGGER.error("Exception encountered", e);
            processor.shutdownNow();
        } finally {
            processor.shutdown();
        }
    }

    private void process(CpuIntensiveProcessor<String, String> processor, java.util.concurrent.BlockingQueue<java.util.concurrent.Future<java.util.Collection<String>>> queue, BufferedWriter writer) throws InterruptedException, java.util.concurrent.ExecutionException, IOException {
        while (true) {
            var hadFinished = processor.hasLoadingFinished();
            var batch = queue.poll(1, TimeUnit.SECONDS);
            if (batch != null) {
                // will throw if batch was a failure
                for (String line : batch.get()) {
                    writer.append(line);
                    writer.newLine();
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
        var spout = "gz".equals(ext.toLowerCase()) ? new GZIPInputStream(file) : file;
        var br = new BufferedReader(new InputStreamReader(spout, Charsets.UTF_8));
        return br.lines();
    }

}
