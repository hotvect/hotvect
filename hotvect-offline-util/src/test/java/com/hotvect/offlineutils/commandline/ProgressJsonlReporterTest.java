package com.hotvect.offlineutils.commandline;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.dropwizard.DropwizardConfig;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressJsonlReporterTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesStructuredProgressEvents() throws Exception {
        MetricRegistry metricRegistry = new MetricRegistry();
        DropwizardConfig dropwizardConfig = new DropwizardConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String prefix() {
                return "";
            }
        };
        DropwizardMeterRegistry meterRegistry = new DropwizardMeterRegistry(
                dropwizardConfig,
                metricRegistry,
                HierarchicalNameMapper.DEFAULT,
                Clock.SYSTEM
        ) {
            @Override
            protected Double nullGaugeValue() {
                return Double.NaN;
            }
        };
        io.micrometer.core.instrument.Timer timer = io.micrometer.core.instrument.Timer
                .builder("ordered.file.mapper.records")
                .register(meterRegistry);
        timer.record(10, java.util.concurrent.TimeUnit.MILLISECONDS);
        timer.record(20, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(Set.of("orderedFileMapperRecords"), metricRegistry.getTimers().keySet());

        File metadataDir = tempDir.resolve("metadata").toFile();
        java.nio.file.Files.createDirectories(metadataDir.toPath());
        Options opts = new Options();
        opts.metadataLocation = metadataDir;
        opts.destinationFile = tempDir.resolve("encoded").toFile();
        Path source = tempDir.resolve("dt=2000-04-28/input.jsonl");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.writeString(source, "{}\n");
        opts.sourceFiles = Map.of("default", List.of(source.getParent().toFile()));
        AlgorithmDefinition algorithmDefinition = AlgorithmDefinition.externalAlgorithm("example-ranker");

        ProgressJsonlReporter reporter = new ProgressJsonlReporter(
                metricRegistry,
                OM,
                metadataDir,
                "encode",
                opts,
                algorithmDefinition
        );

        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            reporter.start();
            reporter.writeProgress();
            reporter.writeEnd(Map.of(
                    "lines_read", 3L,
                    "lines_written", 2L,
                    "number_of_files_read", 1,
                    "mean_throughput", 42.0
            ));
            reporter.writeProgress();
            reporter.close();
        } finally {
            System.setOut(originalOut);
        }

        List<String> lines = java.nio.file.Files.readAllLines(metadataDir.toPath().resolve("progress.jsonl"));
        assertEquals(3, lines.size());

        JsonNode begin = OM.readTree(lines.get(0));
        assertEquals("begin", begin.get("event_type").asText());
        assertEquals("encode", begin.get("stage").asText());
        assertEquals("example-ranker", begin.get("algorithm_name").asText());
        assertEquals("2000-04-28", begin.get("current_dt").asText());
        assertEquals(1, begin.get("input_files_total").asLong());

        JsonNode progress = OM.readTree(lines.get(1));
        assertEquals("progress", progress.get("event_type").asText());
        assertEquals(2, progress.get("records_processed").asLong());
        assertEquals(2, progress.at("/metrics/timers/orderedFileMapperRecords/count").asLong());

        JsonNode end = OM.readTree(lines.get(2));
        assertEquals("end", end.get("event_type").asText());
        assertEquals(2, end.get("records_processed").asLong());
        assertEquals(42.0, end.get("throughput").asDouble());
        assertEquals(3, end.get("records_read").asLong());
        assertEquals(2, end.get("records_written").asLong());
        assertEquals(1, end.get("input_files_done").asInt());

        List<String> statusLines = stdout.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(3, statusLines.size());
        for (int i = 0; i < statusLines.size(); i++) {
            String statusLine = statusLines.get(i);
            assertEquals(ProgressJsonlReporter.STATUS_LOG_PREFIX + lines.get(i), statusLine);
        }
    }

    @Test
    void writesProgressForBatchedEncodeMappings() throws Exception {
        MetricRegistry metricRegistry = new MetricRegistry();
        metricRegistry.timer("orderedFileMapperRecords").update(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        File metadataDir = tempDir.resolve("batched-encode-metadata").toFile();
        java.nio.file.Files.createDirectories(metadataDir.toPath());

        Path firstSource = tempDir.resolve("dt=2026-04-28/first.jsonl");
        Path secondSource = tempDir.resolve("dt=2026-04-29/second.jsonl");
        java.nio.file.Files.createDirectories(firstSource.getParent());
        java.nio.file.Files.createDirectories(secondSource.getParent());
        java.nio.file.Files.writeString(firstSource, "{}\n");
        java.nio.file.Files.writeString(secondSource, "{}\n");

        Path firstDestination = tempDir.resolve("encoded/dt=2026-04-28");
        Path secondDestination = tempDir.resolve("encoded/dt=2026-04-29");
        Options opts = new Options();
        opts.metadataLocation = metadataDir;
        opts.sourceDestMappings = List.of(
                new SourceDestMapping(List.of(firstSource.toFile()), firstDestination.toFile()),
                new SourceDestMapping(List.of(secondSource.toFile()), secondDestination.toFile())
        );

        ProgressJsonlReporter reporter = new ProgressJsonlReporter(
                metricRegistry,
                OM,
                metadataDir,
                "encode",
                opts,
                AlgorithmDefinition.externalAlgorithm("article-ranker")
        );

        reporter.start();
        reporter.writeEnd(Map.of(
                "source_dest_mappings",
                List.of(
                        Map.of(
                                "sources", List.of(firstSource.toString()),
                                "dest", firstDestination.toString(),
                                "metadata", Map.of(
                                        "records_processed", 7L,
                                        "records_processed_at_rate", 99.0,
                                        "lines_read", 8L,
                                        "lines_written", 7L,
                                        "number_of_files_read", 1L
                                )
                        ),
                        Map.of(
                                "sources", List.of(secondSource.toString()),
                                "dest", secondDestination.toString(),
                                "metadata", Map.of(
                                        "records_processed", 11L,
                                        "records_processed_at_rate", 101.0,
                                        "lines_read", 13L,
                                        "lines_written", 10L,
                                        "number_of_files_read", 1L
                                )
                        )
                )
        ));
        reporter.close();

        List<String> lines = java.nio.file.Files.readAllLines(metadataDir.toPath().resolve("progress.jsonl"));
        assertEquals(2, lines.size());

        JsonNode begin = OM.readTree(lines.get(0));
        assertTrue(begin.get("destination_file").isNull());
        assertEquals(2, begin.get("input_files_total").asLong());
        assertEquals(
                firstSource.toAbsolutePath().toString(),
                begin.at("/source_files/default/0").asText()
        );
        assertEquals(
                secondSource.toAbsolutePath().toString(),
                begin.at("/source_files/default/1").asText()
        );
        assertEquals("2026-04-28", begin.at("/input_dates/0").asText());
        assertEquals("2026-04-29", begin.at("/input_dates/1").asText());
        assertTrue(begin.get("current_dt").isNull());
        assertEquals(
                firstDestination.toAbsolutePath().toString(),
                begin.at("/source_dest_mappings/0/dest").asText()
        );
        assertEquals(
                secondDestination.toAbsolutePath().toString(),
                begin.at("/source_dest_mappings/1/dest").asText()
        );

        JsonNode end = OM.readTree(lines.get(1));
        assertEquals("end", end.get("event_type").asText());
        assertEquals(
                secondDestination.toAbsolutePath().toString(),
                end.at("/source_dest_mappings/1/dest").asText()
        );
        assertEquals(18L, end.get("records_processed").asLong());
        assertEquals(21L, end.get("records_read").asLong());
        assertEquals(17L, end.get("records_written").asLong());
        assertEquals(2L, end.get("input_files_done").asLong());
        assertEquals(
                end.at("/metrics/timers/orderedFileMapperRecords/m1_rate").asDouble(),
                end.get("throughput").asDouble()
        );
    }

    @Test
    void writesFailureDetails() throws Exception {
        MetricRegistry metricRegistry = new MetricRegistry();
        File metadataDir = tempDir.resolve("failure-metadata").toFile();
        java.nio.file.Files.createDirectories(metadataDir.toPath());

        Options opts = new Options();
        opts.metadataLocation = metadataDir;
        AlgorithmDefinition algorithmDefinition = AlgorithmDefinition.externalAlgorithm("example-ranker");

        ProgressJsonlReporter reporter = new ProgressJsonlReporter(
                metricRegistry,
                OM,
                metadataDir,
                "predict",
                opts,
                algorithmDefinition
        );

        reporter.start();
        reporter.writeFailure(new IllegalStateException("broken"));
        reporter.close();

        List<String> lines = java.nio.file.Files.readAllLines(metadataDir.toPath().resolve("progress.jsonl"));
        JsonNode failure = OM.readTree(lines.get(1));
        assertEquals("failure", failure.get("event_type").asText());
        assertEquals("java.lang.IllegalStateException", failure.get("error_class").asText());
        assertEquals("broken", failure.get("error_message").asText());
    }

    @Test
    void doesNotInferProgressFromArbitraryProcessorMetric() throws Exception {
        MetricRegistry metricRegistry = new MetricRegistry();
        metricRegistry.timer("algorithm.processor").update(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        File metadataDir = tempDir.resolve("strict-metric-metadata").toFile();
        java.nio.file.Files.createDirectories(metadataDir.toPath());

        Options opts = new Options();
        opts.metadataLocation = metadataDir;
        AlgorithmDefinition algorithmDefinition = AlgorithmDefinition.externalAlgorithm("example-ranker");
        ProgressJsonlReporter reporter = new ProgressJsonlReporter(
                metricRegistry,
                OM,
                metadataDir,
                "predict",
                opts,
                algorithmDefinition
        );

        reporter.start();
        reporter.writeProgress();
        reporter.writeFailure(new IllegalStateException("stop"));

        List<String> lines = java.nio.file.Files.readAllLines(metadataDir.toPath().resolve("progress.jsonl"));
        assertNull(OM.readTree(lines.get(1)).get("records_processed").textValue());
    }
}
