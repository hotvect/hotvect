package com.hotvect.offlineutils.commandline;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metered;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.onlineutils.concurrency.fileutils.FileUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProgressJsonlReporter implements AutoCloseable {
    static final String PROGRESS_FILENAME = "progress.jsonl";
    static final String STATUS_LOG_PREFIX = "HOTVECT_STATUS ";
    private static final long PROGRESS_INTERVAL_SECONDS = 60L;
    private static final Pattern DATE_PARTITION = Pattern.compile("(?:^|[/_-])dt=(\\d{4}-\\d{2}-\\d{2})(?:[/_-]|$)");
    private static final Set<String> RECORD_PROGRESS_TIMER_NAMES = Set.of(
            "orderedFileMapperRecords",
            "unorderedFileMapperProcessor",
            "FileReducerProcessor",
            "UnorderedFileAggregatorProcessor"
    );

    private final MetricRegistry metricRegistry;
    private final ObjectMapper objectMapper;
    private final File progressFile;
    private final Map<String, Object> context;
    private final Instant startedAt;
    private final ScheduledExecutorService executor;
    private boolean acceptingProgress = true;
    private boolean terminalEventWritten;

    ProgressJsonlReporter(
            MetricRegistry metricRegistry,
            ObjectMapper objectMapper,
            File metadataDir,
            String stage,
            Options opts,
            AlgorithmDefinition algorithmDefinition
    ) {
        this.metricRegistry = metricRegistry;
        this.objectMapper = objectMapper;
        this.progressFile = new File(metadataDir, PROGRESS_FILENAME);
        this.startedAt = Instant.now();
        this.context = buildContext(stage, opts, algorithmDefinition, metadataDir);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "hotvect-progress-jsonl-reporter");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized void start() {
        writeEvent("begin", null);
        executor.scheduleAtFixedRate(
                this::writeProgress,
                PROGRESS_INTERVAL_SECONDS,
                PROGRESS_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    synchronized void writeProgress() {
        if (acceptingProgress) {
            writeEvent("progress", null);
        }
    }

    synchronized void writeEnd(Map<String, Object> metadata) {
        writeTerminalEvent("end", metadata);
    }

    synchronized void writeFailure(Throwable error) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("error_class", error.getClass().getName());
        failure.put("error_message", error.getMessage());
        writeTerminalEvent("failure", failure);
    }

    @Override
    public synchronized void close() {
        acceptingProgress = false;
        executor.shutdownNow();
    }

    private void writeTerminalEvent(String eventType, Map<String, Object> metadata) {
        if (terminalEventWritten) {
            throw new IllegalStateException("Progress reporter already wrote a terminal event");
        }
        acceptingProgress = false;
        executor.shutdownNow();
        writeEvent(eventType, metadata);
        terminalEventWritten = true;
    }

    private void writeEvent(String eventType, Map<String, Object> metadata) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_type", eventType);
        event.putAll(context);

        Instant now = Instant.now();
        event.put("started_at", startedAt.toString());
        event.put("updated_at", now.toString());
        event.put("elapsed_seconds", Duration.between(startedAt, now).toMillis() / 1000.0);

        Map<String, Object> metrics = metricsSnapshot();
        event.put("records_processed", processedRecordCount(metrics).orElse(null));
        event.put("throughput", processedThroughput(metrics).orElse(null));
        event.put("metrics", metrics);

        if ("failure".equals(eventType) && metadata != null) {
            event.putAll(metadata);
        } else if (metadata != null) {
            Map<String, Object> summarizedMetadata = summarizedMetadata(metadata);
            event.put(
                    "records_processed",
                    firstMetadataValue(summarizedMetadata, event.get("records_processed"), "records_processed", "total_record_count", "lines_written", "lines_read")
            );
            event.put(
                    "throughput",
                    firstMetadataValue(summarizedMetadata, event.get("throughput"), "records_processed_at_rate", "lines_written_at_rate", "lines_read_at_rate", "mean_throughput")
            );
            event.put("records_read", firstMetadataValue(summarizedMetadata, null, "lines_read"));
            event.put("records_written", firstMetadataValue(summarizedMetadata, null, "lines_written", "total_record_count"));
            event.put("input_files_done", summarizedMetadata.get("number_of_files_read"));
            event.put("final_metadata_keys", metadata.keySet());
        }

        append(event);
    }

    private void append(Map<String, Object> event) {
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize progress event", e);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                progressFile.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            writer.write(serialized);
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write progress JSONL: " + progressFile, e);
        }
        System.out.println(STATUS_LOG_PREFIX + serialized);
        System.out.flush();
    }

    private Map<String, Object> metricsSnapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("counters", counterSnapshot());
        metrics.put("gauges", gaugeSnapshot());
        metrics.put("histograms", histogramSnapshot());
        metrics.put("meters", meterSnapshot());
        metrics.put("timers", timerSnapshot());
        return metrics;
    }

    private Map<String, Object> counterSnapshot() {
        Map<String, Object> counters = new LinkedHashMap<>();
        metricRegistry.getCounters().forEach((name, counter) -> counters.put(name, counter.getCount()));
        return counters;
    }

    private Map<String, Object> gaugeSnapshot() {
        Map<String, Object> gauges = new LinkedHashMap<>();
        metricRegistry.getGauges().forEach((name, gauge) -> gauges.put(name, safeGaugeValue(gauge)));
        return gauges;
    }

    private Object safeGaugeValue(Gauge<?> gauge) {
        Object value = gauge.getValue();
        if (value instanceof Number number) {
            return finite(number.doubleValue());
        }
        return value;
    }

    private Map<String, Object> histogramSnapshot() {
        Map<String, Object> histograms = new LinkedHashMap<>();
        metricRegistry.getHistograms().forEach((name, histogram) -> histograms.put(name, histogramMetrics(histogram)));
        return histograms;
    }

    private Map<String, Object> meterSnapshot() {
        Map<String, Object> meters = new LinkedHashMap<>();
        metricRegistry.getMeters().forEach((name, meter) -> meters.put(name, meterMetrics(meter)));
        return meters;
    }

    private Map<String, Object> timerSnapshot() {
        Map<String, Object> timers = new LinkedHashMap<>();
        metricRegistry.getTimers().forEach((name, timer) -> timers.put(name, timerMetrics(timer)));
        return timers;
    }

    private Map<String, Object> histogramMetrics(Histogram histogram) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("count", histogram.getCount());
        Snapshot snapshot = histogram.getSnapshot();
        metrics.put("min", snapshot.getMin());
        metrics.put("max", snapshot.getMax());
        metrics.put("mean", finite(snapshot.getMean()));
        metrics.put("p50", finite(snapshot.getMedian()));
        metrics.put("p95", finite(snapshot.get95thPercentile()));
        metrics.put("p99", finite(snapshot.get99thPercentile()));
        return metrics;
    }

    private Map<String, Object> meterMetrics(Metered meter) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("count", meter.getCount());
        metrics.put("mean_rate", finite(meter.getMeanRate()));
        metrics.put("m1_rate", finite(meter.getOneMinuteRate()));
        metrics.put("m5_rate", finite(meter.getFiveMinuteRate()));
        metrics.put("m15_rate", finite(meter.getFifteenMinuteRate()));
        return metrics;
    }

    private Map<String, Object> timerMetrics(Timer timer) {
        Map<String, Object> metrics = meterMetrics(timer);
        Snapshot snapshot = timer.getSnapshot();
        metrics.put("min_ms", nanosToMillis(snapshot.getMin()));
        metrics.put("max_ms", nanosToMillis(snapshot.getMax()));
        metrics.put("mean_ms", nanosToMillis(snapshot.getMean()));
        metrics.put("p50_ms", nanosToMillis(snapshot.getMedian()));
        metrics.put("p95_ms", nanosToMillis(snapshot.get95thPercentile()));
        metrics.put("p99_ms", nanosToMillis(snapshot.get99thPercentile()));
        return metrics;
    }

    private Optional<Long> processedRecordCount(Map<String, Object> metrics) {
        return selectedMetric(metrics).map(metric -> ((Number) metric.get("count")).longValue());
    }

    private Optional<Object> processedThroughput(Map<String, Object> metrics) {
        return selectedMetric(metrics).map(metric -> metric.get("m1_rate"));
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> selectedMetric(Map<String, Object> metrics) {
        Optional<Map<String, Object>> timerMetric = selectedMetric(metrics, "timers");
        if (timerMetric.isPresent()) {
            return timerMetric;
        }
        return selectedMetric(metrics, "meters");
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> selectedMetric(Map<String, Object> metrics, String metricGroupName) {
        Map<String, Object> metricGroup = (Map<String, Object>) metrics.get(metricGroupName);
        if (metricGroup == null) {
            return Optional.empty();
        }
        List<Map.Entry<String, Object>> matchingMetrics = metricGroup.entrySet()
                .stream()
                .filter(entry -> RECORD_PROGRESS_TIMER_NAMES.contains(entry.getKey()))
                .toList();
        if (matchingMetrics.size() > 1) {
            throw new IllegalStateException(
                    "Expected at most one record progress metric, found: "
                            + matchingMetrics.stream().map(Map.Entry::getKey).toList()
            );
        }
        return matchingMetrics.stream().findFirst().map(entry -> (Map<String, Object>) entry.getValue());
    }

    private static Object firstMetadataValue(Map<String, Object> metadata, Object fallback, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private static Map<String, Object> summarizedMetadata(Map<String, Object> metadata) {
        if (!metadata.containsKey("source_dest_mappings")) {
            return metadata;
        }

        List<Map<String, Object>> mappingMetadata = mappingMetadata(metadata);
        Map<String, Object> summary = new LinkedHashMap<>(metadata);
        addSummedMetadataValue(
                summary,
                mappingMetadata,
                "records_processed",
                "records_processed",
                "total_record_count",
                "lines_written",
                "lines_read"
        );
        addSummedMetadataValue(summary, mappingMetadata, "lines_read", "lines_read");
        addSummedMetadataValue(summary, mappingMetadata, "lines_written", "lines_written", "total_record_count");
        addSummedMetadataValue(summary, mappingMetadata, "number_of_files_read", "number_of_files_read");
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mappingMetadata(Map<String, Object> metadata) {
        return ((List<Map<String, Object>>) metadata.get("source_dest_mappings"))
                .stream()
                .map(mapping -> (Map<String, Object>) mapping.get("metadata"))
                .toList();
    }

    private static void addSummedMetadataValue(
            Map<String, Object> summary,
            List<Map<String, Object>> mappingMetadata,
            String summaryKey,
            String... metadataKeys
    ) {
        long total = 0L;
        boolean found = false;
        for (Map<String, Object> mapping : mappingMetadata) {
            Object value = firstMetadataValue(mapping, null, metadataKeys);
            if (value != null) {
                total += ((Number) value).longValue();
                found = true;
            }
        }
        if (found) {
            summary.put(summaryKey, total);
        }
    }

    private static Map<String, Object> buildContext(
            String stage,
            Options opts,
            AlgorithmDefinition algorithmDefinition,
            File metadataDir
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stage", stage);
        context.put("algorithm_name", algorithmDefinition.algorithmId().algorithmName());
        context.put("algorithm_version", algorithmDefinition.algorithmId().algorithmVersion());
        context.put("metadata_path", metadataDir.getAbsolutePath());
        context.put("destination_file", destinationFile(opts));

        Map<String, List<File>> inputSources = inputSources(opts);
        context.put("source_files", sourceFiles(inputSources));
        context.put("input_files_total", inputFileCount(inputSources));

        Set<String> dates = sourceDates(inputSources);
        context.put("input_dates", dates);
        context.put("current_dt", dates.size() == 1 ? dates.iterator().next() : null);
        if (!opts.sourceDestMappings.isEmpty()) {
            context.put("source_dest_mappings", sourceDestMappings(opts.sourceDestMappings));
        }
        return context;
    }

    private static String destinationFile(Options opts) {
        if (opts.destinationFile != null) {
            return opts.destinationFile.getAbsolutePath();
        }
        if (opts.sourceDestMappings.size() == 1) {
            return opts.sourceDestMappings.get(0).dest().getAbsolutePath();
        }
        return null;
    }

    private static Map<String, List<File>> inputSources(Options opts) {
        if (opts.sourceDestMappings.isEmpty()) {
            return opts.sourceFiles;
        }
        return Map.of(
                "default",
                opts.sourceDestMappings.stream().flatMap(mapping -> mapping.sources().stream()).toList()
        );
    }

    private static List<Map<String, Object>> sourceDestMappings(List<SourceDestMapping> mappings) {
        return mappings.stream().map(mapping -> {
            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("sources", mapping.sources().stream().map(File::getAbsolutePath).toList());
            formatted.put("dest", mapping.dest().getAbsolutePath());
            return formatted;
        }).toList();
    }

    private static Map<String, List<String>> sourceFiles(Map<String, List<File>> sourceFiles) {
        Map<String, List<String>> formatted = new LinkedHashMap<>();
        sourceFiles.forEach((name, files) -> {
            List<String> paths = new ArrayList<>();
            files.forEach(file -> paths.add(file.getAbsolutePath()));
            formatted.put(name, paths);
        });
        return formatted;
    }

    private static long inputFileCount(Map<String, List<File>> sourceFiles) {
        return sourceFiles.values().stream().mapToLong(files -> FileUtils.listFiles(files).count()).sum();
    }

    private static Set<String> sourceDates(Map<String, List<File>> sourceFiles) {
        Set<String> dates = new LinkedHashSet<>();
        sourceFiles.values().forEach(files -> files.forEach(file -> {
            Matcher matcher = DATE_PARTITION.matcher(file.getPath());
            while (matcher.find()) {
                dates.add(matcher.group(1));
            }
        }));
        return dates;
    }

    private static Object finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static Object nanosToMillis(double nanos) {
        return finite(nanos / 1_000_000.0);
    }
}
