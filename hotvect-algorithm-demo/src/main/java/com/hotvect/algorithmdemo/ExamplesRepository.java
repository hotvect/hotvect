package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.algorithmserver.ContractViolationException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

public final class ExamplesRepository {
    private static final ObjectMapper OM = new ObjectMapper();

    private final List<ExampleRecord> examples;

    private ExamplesRepository(List<ExampleRecord> examples) {
        this.examples = List.copyOf(examples);
    }

    public static ExamplesRepository empty() {
        return new ExamplesRepository(List.of());
    }

    public static ExamplesRepository loadFromDirectory(File dir) throws IOException {
        return loadFromDirectory(dir, Integer.MAX_VALUE);
    }

    public static ExamplesRepository loadFromDirectory(File dir, int maxExamples) throws IOException {
        require(dir.exists() && dir.isDirectory(), "Not a directory: %s", dir.getAbsolutePath());
        require(maxExamples >= 1, "maxExamples must be >= 1, got %s", maxExamples);

        Path root = dir.toPath();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(ExamplesRepository::isExampleFile)
                    .sorted()
                    .toList();
        }

        List<ExampleRecord> examples = new ArrayList<>();
        int nextId = 0;

        load:
        for (Path file : files) {
            String name = file.getFileName().toString();
            boolean isJsonl = name.endsWith(".jsonl")
                    || name.endsWith(".ndjson")
                    || name.endsWith(".jsonl.gz")
                    || name.endsWith(".ndjson.gz")
                    // Spark/Databricks JSON datasets are often newline-delimited with "*.json.gz".
                    || name.endsWith(".json.gz");

            if (isJsonl) {
                try (BufferedReader reader = newExampleReader(file)) {
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (line.isBlank()) {
                            continue;
                        }
                        String exampleId = extractTopLevelExampleIdOrNull(file + ":" + lineNumber, line);
                        String source = root.relativize(file).toString() + ":" + lineNumber;

                        examples.add(new ExampleRecord(nextId++, exampleId, source, line));
                        if (examples.size() >= maxExamples) {
                            break load;
                        }
                    }
                }
            } else {
                String raw = readAllUtf8(file).trim();
                if (raw.isBlank()) {
                    throw new ContractViolationException("Empty JSON examples file: " + file, null);
                }
                ObjectNode obj = parseExampleObjectOrThrow(file + ":1", raw);

                String exampleId = nonEmptyStringField(obj, "example_id").orElse(null);
                String source = root.relativize(file).toString() + ":1";
                examples.add(new ExampleRecord(nextId++, exampleId, source, raw));
                if (examples.size() >= maxExamples) {
                    break;
                }
            }
        }

        return new ExamplesRepository(examples);
    }

    public int size() {
        return examples.size();
    }

    public ExampleRecord getById(int exampleIndex) {
        if (exampleIndex < 0 || exampleIndex >= examples.size()) {
            return null;
        }
        ExampleRecord record = examples.get(exampleIndex);
        if (record.id != exampleIndex) {
            // Should never happen, but fail fast if we ever reorder/modify storage
            throw new IllegalStateException("Example index mismatch: expected " + exampleIndex + " but got " + record.id);
        }
        return record;
    }

    public List<ExampleSummary> summaries(int limit) {
        int n = limit <= 0 ? 100 : limit;
        return examples.stream().limit(n).map(ExampleSummary::from).toList();
    }

    public record ExampleRecord(int id, String exampleId, String source, String rawJson) {
        public ExampleRecord {
            Objects.requireNonNull(source);
            Objects.requireNonNull(rawJson);
        }
    }

    public record ExampleSummary(int example_index, String example_id, String source, String preview) {
        static ExampleSummary from(ExampleRecord record) {
            return of(record, record.exampleId);
        }

        static ExampleSummary of(ExampleRecord record, String exampleIdOrNull) {
            return new ExampleSummary(record.id, exampleIdOrNull, record.source, previewOf(record.rawJson));
        }
    }

    private static String previewOf(String rawJson) {
        String s = Objects.requireNonNull(rawJson).trim();
        if (s.length() <= 160) {
            return s;
        }
        return s.substring(0, 160) + "…";
    }

    private static boolean isExampleFile(Path p) {
        String name = p.getFileName().toString();
        // Exclude dataset sidecar schema files commonly present in Spark/Databricks JSON datasets.
        // These are not newline-delimited example records and can be large/unexpected.
        if (name.equals("_schema.json") || name.equals("_schema.json.gz")) {
            return false;
        }
        return name.endsWith(".jsonl")
                || name.endsWith(".ndjson")
                || name.endsWith(".json")
                || name.endsWith(".jsonl.gz")
                || name.endsWith(".ndjson.gz")
                || name.endsWith(".json.gz");
    }

    private static BufferedReader newExampleReader(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".gz")) {
            InputStream is = new GZIPInputStream(Files.newInputStream(file));
            return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    private static String readAllUtf8(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".gz")) {
            try (InputStream is = new GZIPInputStream(Files.newInputStream(file))) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static ObjectNode parseExampleObjectOrThrow(String location, String json) {
        JsonNode parsed;
        try {
            parsed = OM.readTree(json);
        } catch (Exception e) {
            throw new ContractViolationException(
                    "Invalid JSON in examples file: " + location,
                    e.getMessage()
            );
        }
        if (!(parsed instanceof ObjectNode obj)) {
            throw new ContractViolationException(
                    "Example JSON must be an object: " + location,
                    null
            );
        }
        return obj;
    }

    private static Optional<String> nonEmptyStringField(ObjectNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(n.asText());
    }

    private static void require(boolean condition, String messageTemplate, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException(String.format(messageTemplate, args));
        }
    }

    private static String extractTopLevelExampleIdOrNull(String location, String rawJson) {
        String exampleId = null;
        try (JsonParser p = OM.getFactory().createParser(rawJson)) {
            JsonToken first = p.nextToken();
            if (first != JsonToken.START_OBJECT) {
                throw new ContractViolationException("Example JSON must be an object: " + location, null);
            }

            while (p.nextToken() != null) {
                if (p.currentToken() != JsonToken.FIELD_NAME) {
                    continue;
                }

                String field = p.currentName();
                JsonToken valueToken = p.nextToken();
                if (valueToken == null) {
                    break;
                }

                if ("example_id".equals(field) && valueToken == JsonToken.VALUE_STRING && exampleId == null) {
                    String v = p.getValueAsString();
                    if (v != null && !v.isBlank()) {
                        exampleId = v;
                    }
                }

                if (valueToken == JsonToken.START_OBJECT || valueToken == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        } catch (ContractViolationException e) {
            throw e;
        } catch (Exception e) {
            throw new ContractViolationException("Invalid JSON in examples file: " + location, e.getMessage());
        }

        return exampleId;
    }
}
