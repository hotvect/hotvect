package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.serve.ServeApplication;
import com.hotvect.serve.ContractViolationException;
import com.hotvect.serve.JsonFieldSupport;
import com.hotvect.serve.RequestBodyTooLargeException;
import com.hotvect.serve.ServerExtension;
import com.hotvect.serve.ValidationSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@RestController
final class DemoUiExtension implements ServerExtension {
    private static final Logger log = LoggerFactory.getLogger(DemoUiExtension.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final int MAX_EXAMPLES_LISTED = 100;

    private final Options opts;
    private final DemoSqliteCache sqliteCache;
    private final ExamplesRepository examples;
    private final ActionMetadataRepository actionMetadata;
    private final Map<DecodedExampleIdCacheKey, String> decodedExampleIdCache = new ConcurrentHashMap<>();
    private OfflineExampleExecutor offlineExamples;
    private DemoComparisonService comparisonService;
    private ServeApplication runtime;

    DemoUiExtension(Options opts) throws Exception {
        this.opts = Objects.requireNonNull(opts);
        ValidationSupport.requireArgument(opts.ui, "DemoUiExtension requires --ui");
        ValidationSupport.requireArgument(opts.sourcePath != null, "--source-path is required with --ui");
        ValidationSupport.requireArgument(
                opts.sourcePath.exists() && opts.sourcePath.isDirectory(),
                "--source-path must be a directory: %s",
                opts.sourcePath.getAbsolutePath());
        if (opts.actionMetadataPath != null) {
            ValidationSupport.requireArgument(
                    opts.actionMetadataPath.exists() && opts.actionMetadataPath.isDirectory(),
                    "--action-metadata-path must be a directory: %s",
                    opts.actionMetadataPath.getAbsolutePath());
        }

        this.examples = ExamplesRepository.loadFromDirectory(opts.sourcePath, MAX_EXAMPLES_LISTED);
        this.sqliteCache = DemoSqliteCache.openOrBuild(opts);
        this.actionMetadata = sqliteCache.actionMetadata();
    }

    ActionMetadataLookup actionMetadata() {
        return actionMetadata;
    }

    @Override
    public void initialize(ServeApplication runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        offlineExamples = new OfflineExampleExecutor(runtime, actionMetadata);
        comparisonService = new DemoComparisonService(runtime, actionMetadata, offlineExamples);
    }

    @Override
    public void addMetadata(ObjectNode root) {
        root.put("ui_enabled", true);
        root.put("json_in_string_auto", true);
        JsonFieldSupport.putStringOrNull(root, "default_select_json_path", normalizedDefaultSelectJsonPath());
        root.put("source_path", opts.sourcePath.getAbsolutePath());
        root.put("demo_sqlite_path", sqliteCache.dbPath().toAbsolutePath().toString());
        root.put("demo_sqlite_built_now", sqliteCache.builtNow());
        root.put("examples_count", examples.size());

        ObjectNode actionMetadataNode = root.putObject("action_metadata");
        actionMetadataNode.put("enabled", actionMetadata.isEnabled());
        actionMetadataNode.put("count", actionMetadata.size());
        if (opts.actionMetadataPath == null) {
            actionMetadataNode.putNull("path");
        } else {
            actionMetadataNode.put("path", opts.actionMetadataPath.getAbsolutePath());
        }
    }

    @Override
    public void onStarted(String baseUrl) {
        log.info("Demo UI enabled at {}", baseUrl);
        log.info("SQLite cache: {}", sqliteCache.dbPath());
        log.info("Examples loaded: {}", examples.size());
        log.info("Action metadata loaded: {}", actionMetadata.size());
    }

    @Override
    public void close() {
        sqliteCache.close();
    }

    @GetMapping("/api/demo/examples")
    ResponseEntity<JsonNode> examplesList(
            @RequestParam(name = "limit", required = false) String rawLimit,
            @RequestParam(name = "algorithm_runtime_id", required = false) String rawAlgorithmRuntimeId) {
        try {
            int limit = rawLimit == null ? MAX_EXAMPLES_LISTED : parseInt(rawLimit, "limit");
            if (limit < 1 || limit > MAX_EXAMPLES_LISTED) {
                throw new ContractViolationException("limit must be between 1 and " + MAX_EXAMPLES_LISTED, rawLimit);
            }
            int count = Math.min(limit, examples.size());
            String algorithmRuntimeId = JsonFieldSupport.blankToNull(rawAlgorithmRuntimeId);
            List<ExamplesRepository.ExampleSummary> shown = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ExamplesRepository.ExampleRecord record = examples.getById(i);
                String exampleId = decodedExampleIdOrFallback(record, algorithmRuntimeId);
                shown.add(ExamplesRepository.ExampleSummary.of(record, exampleId));
            }
            ObjectNode node = OM.createObjectNode();
            node.put("count", examples.size());
            node.put("limit", limit);
            node.set("examples", OM.valueToTree(shown));
            return response(HttpStatus.OK, node);
        } catch (ContractViolationException e) {
            return response(HttpStatus.BAD_REQUEST, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, ServeApplication.error("Unhandled error", e.getMessage()));
        }
    }

    @GetMapping("/api/demo/examples/{example_index}")
    ResponseEntity<JsonNode> example(
            @PathVariable("example_index") String rawExampleIndex,
            @RequestParam(name = "algorithm_runtime_id", required = false) String rawAlgorithmRuntimeId) {
        try {
            int exampleIndex = parseInt(rawExampleIndex, "example_index");
            ExamplesRepository.ExampleRecord record = examples.getById(exampleIndex);
            if (record == null) {
                return response(
                        HttpStatus.NOT_FOUND,
                        ServeApplication.error("Unknown example_index: " + rawExampleIndex, null));
            }
            String algorithmRuntimeId = JsonFieldSupport.blankToNull(rawAlgorithmRuntimeId);
            ObjectNode node = OM.createObjectNode();
            node.put("example_index", record.id());
            node.put("source", record.source());
            String exampleId = decodedExampleIdOrFallback(record, algorithmRuntimeId);
            JsonFieldSupport.putStringOrNull(node, "example_id", exampleId);
            ObjectNode raw = ExamplesRepository.parseExampleObjectOrThrow(record.source(), record.rawJson()).deepCopy();
            DemoComparisonService.ExampleViewData viewData = comparisonService.exampleViewData(raw);
            node.set("shown_candidates", viewData.shownCandidates());
            node.set("views", viewData.views());
            node.set("default_view_ids", OM.valueToTree(viewData.defaultViewIds()));
            ObjectNode json = raw.deepCopy();
            JsonInStringSupport.injectVirtualJsonFields(json);
            node.set("json", json);
            return response(HttpStatus.OK, node);
        } catch (ContractViolationException e) {
            return response(HttpStatus.BAD_REQUEST, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, ServeApplication.error("Unhandled error", e.getMessage()));
        }
    }

    @GetMapping("/api/demo/action-metadata/{action_id}")
    ResponseEntity<JsonNode> actionMetadata(@PathVariable("action_id") String actionId) {
        if (!actionMetadata.isEnabled()) {
            return response(
                    HttpStatus.BAD_REQUEST,
                    ServeApplication.error(
                            "Action metadata is disabled (start Demo UI with --action-metadata-path)",
                            null));
        }
        try {
            JsonNode node = actionMetadata.getJsonOrFallbackIfEnabled(actionId);
            return response(HttpStatus.OK, node);
        } catch (ContractViolationException e) {
            return response(HttpStatus.NOT_FOUND, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            return response(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ServeApplication.error("Failed to load action metadata", e.getMessage()));
        }
    }

    @GetMapping("/api/demo/runtime-metadata")
    ResponseEntity<JsonNode> runtimeMetadata(
            @RequestParam(name = "algorithm_runtime_id", required = false) String rawAlgorithmRuntimeId) {
        try {
            String algorithmRuntimeId = JsonFieldSupport.blankToNull(rawAlgorithmRuntimeId);
            return response(HttpStatus.OK, runtime.buildRuntimeDetails(algorithmRuntimeId));
        } catch (ContractViolationException e) {
            return response(HttpStatus.BAD_REQUEST, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Failed to load runtime metadata", e);
            return response(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ServeApplication.error("Failed to load runtime metadata", e.getMessage()));
        }
    }

    @PostMapping("/api/demo/run")
    ResponseEntity<JsonNode> run(HttpServletRequest request) {
        try {
            RunRequest req = readRunRequest(request);
            DemoRunInput input = parseDemoRunInput(req);
            String expectedExampleId = JsonFieldSupport.nonEmptyStringField(input.exampleNode(), "example_id").orElse(null);
            ObjectNode response = offlineExamples.runExample(
                    input.exampleNode(),
                    expectedExampleId,
                    input.algorithmRuntimeId());
            if (input.exampleRecord() != null) {
                response.put("example_index", input.exampleRecord().id());
                response.put("example_source", input.exampleRecord().source());
            }
            return response(HttpStatus.OK, response);
        } catch (RequestBodyTooLargeException e) {
            return response(HttpStatus.PAYLOAD_TOO_LARGE, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (ExampleNotFoundException e) {
            return response(HttpStatus.NOT_FOUND, ServeApplication.error(e.getMessage(), null));
        } catch (ContractViolationException e) {
            return response(HttpStatus.BAD_REQUEST, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, ServeApplication.error("Unhandled error", e.getMessage()));
        }
    }

    @PostMapping("/api/demo/predict")
    ResponseEntity<JsonNode> demoPredict(HttpServletRequest request) {
        try {
            RunRequest req = readRunRequest(request);
            DemoRunInput input = parseDemoRunInput(req);
            ObjectNode response;
            if (input.viewIds().isEmpty()) {
                response = comparisonService.projectResponse(input.exampleNode(), input.algorithmRuntimeId());
            } else {
                ObjectNode compare = comparisonService.compare(input.exampleNode(), null, input.viewIds());
                JsonNode projected = compare.get("response");
                response = projected instanceof ObjectNode projectedObject
                        ? projectedObject.deepCopy()
                        : OM.createObjectNode();
            }
            return response(HttpStatus.OK, response);
        } catch (RequestBodyTooLargeException e) {
            return response(HttpStatus.PAYLOAD_TOO_LARGE, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (ExampleNotFoundException e) {
            return response(HttpStatus.NOT_FOUND, ServeApplication.error(e.getMessage(), null));
        } catch (ContractViolationException e) {
            return response(HttpStatus.BAD_REQUEST, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, ServeApplication.error("Unhandled error", e.getMessage()));
        }
    }

    @PostMapping("/api/demo/compare")
    ResponseEntity<JsonNode> demoCompare(HttpServletRequest request) {
        try {
            RunRequest req = readRunRequest(request);
            DemoRunInput input = parseDemoRunInput(req);
            ObjectNode response = comparisonService.compare(input.exampleNode(), input.exampleRecord(), input.viewIds());
            return response(HttpStatus.OK, response);
        } catch (RequestBodyTooLargeException e) {
            return response(HttpStatus.PAYLOAD_TOO_LARGE, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (ExampleNotFoundException e) {
            return response(HttpStatus.NOT_FOUND, ServeApplication.error(e.getMessage(), null));
        } catch (ContractViolationException e) {
            return response(HttpStatus.BAD_REQUEST, ServeApplication.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, ServeApplication.error("Unhandled error", e.getMessage()));
        }
    }

    private RunRequest readRunRequest(HttpServletRequest request) throws Exception {
        try {
            return OM.readValue(runtime.readRequestBodyBytes(request), RunRequest.class);
        } catch (RequestBodyTooLargeException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new ContractViolationException("Invalid JSON request body", e.getOriginalMessage());
        } catch (Exception e) {
            throw new ContractViolationException("Invalid request body", e.getMessage());
        }
    }

    private DemoRunInput parseDemoRunInput(RunRequest req) throws Exception {
        ExamplesRepository.ExampleRecord exampleRecord = null;
        if (req.exampleIndex != null) {
            exampleRecord = examples.getById(req.exampleIndex);
            if (exampleRecord == null) {
                throw new ExampleNotFoundException("Unknown example_index: " + req.exampleIndex);
            }
        } else if (req.exampleJson == null) {
            throw new ContractViolationException("Missing required field: example_json or example_index", null);
        }

        if (req.exampleJson != null && req.overrideJson != null && !req.overrideJson.isBlank()) {
            throw new ContractViolationException("Cannot set both example_json and override_json; edit one or the other", null);
        }

        ObjectNode exampleNode;
        if (req.exampleJson != null) {
            if (!req.exampleJson.isObject()) {
                throw new ContractViolationException(
                        "example_json must be a JSON object",
                        "got " + req.exampleJson.getNodeType().name().toLowerCase());
            }
            exampleNode = ((ObjectNode) req.exampleJson).deepCopy();
        } else {
            exampleNode = ExamplesRepository.parseExampleObjectOrThrow(exampleRecord.source(), exampleRecord.rawJson()).deepCopy();
        }

        if (req.overrideJson != null && !req.overrideJson.isBlank()) {
            JsonNode overrideNode;
            try {
                overrideNode = OM.readTree(req.overrideJson);
            } catch (JsonProcessingException e) {
                throw new ContractViolationException("override_json is not valid JSON", e.getOriginalMessage());
            } catch (Exception e) {
                throw new ContractViolationException("override_json is not valid JSON", e.getMessage());
            }
            if (!overrideNode.isObject()) {
                throw new ContractViolationException(
                        "override_json must be a JSON object",
                        "got " + overrideNode.getNodeType().name().toLowerCase());
            }
            JsonInStringSupport.mergeOverride(exampleNode, (ObjectNode) overrideNode);
        }
        JsonInStringSupport.collapseVirtualJsonFields(exampleNode);

        return new DemoRunInput(
                exampleNode,
                exampleRecord,
                JsonFieldSupport.blankToNull(req.algorithmRuntimeId),
                req.normalizedViewIds());
    }

    private String normalizedDefaultSelectJsonPath() {
        return JsonFieldSupport.blankToNull(
                opts.defaultSelectJsonPath == null ? null : opts.defaultSelectJsonPath.trim());
    }

    private String decodedExampleIdOrFallback(
            ExamplesRepository.ExampleRecord record,
            String algorithmRuntimeIdOrNull) {
        if (record == null) {
            return null;
        }
        OfflineExampleExecutor.DecodedExampleId decodedExampleId;
        try {
            decodedExampleId = offlineExamples.tryDecodeExampleIdOrNull(
                    record.rawJson(),
                    algorithmRuntimeIdOrNull);
        } catch (Exception error) {
            throw new ContractViolationException("Failed to create example decoder", error.getMessage());
        }
        DecodedExampleIdCacheKey cacheKey = new DecodedExampleIdCacheKey(
                record.id(),
                decodedExampleId.algorithmRuntimeId());
        return decodedExampleIdCache.computeIfAbsent(cacheKey, _ignored -> {
            String decoded = decodedExampleId.exampleId();
            if (decoded != null && !decoded.isBlank()) {
                return decoded;
            }
            if (record.exampleId() != null && !record.exampleId().isBlank()) {
                return record.exampleId();
            }
            String relevanceId = topLevelTextOrNull(record.rawJson(), "relevance_id");
            if (relevanceId != null && !relevanceId.isBlank()) {
                return relevanceId;
            }
            return topLevelTextOrNull(record.rawJson(), "flow_id");
        });
    }

    private static String topLevelTextOrNull(String rawJson, String field) {
        try {
            JsonNode node = OM.readTree(rawJson);
            if (node == null || !node.isObject()) {
                return null;
            }
            return JsonFieldSupport.textFieldOrNull(node, field);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int parseInt(String s, String field) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ContractViolationException(field + " must be an integer", s);
        }
    }

    private static ResponseEntity<JsonNode> response(HttpStatus status, JsonNode body) {
        return ResponseEntity.status(status).body(body);
    }

    private record DecodedExampleIdCacheKey(int exampleIndex, String algorithmRuntimeId) {
    }

    private record DemoRunInput(
            ObjectNode exampleNode,
            ExamplesRepository.ExampleRecord exampleRecord,
            String algorithmRuntimeId,
            List<String> viewIds
    ) {
    }

    private static final class ExampleNotFoundException extends RuntimeException {
        private ExampleNotFoundException(String message) {
            super(message);
        }
    }

    private static final class RunRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("example_index")
        public Integer exampleIndex;
        @com.fasterxml.jackson.annotation.JsonProperty("override_json")
        public String overrideJson;
        @com.fasterxml.jackson.annotation.JsonProperty("example_json")
        public JsonNode exampleJson;
        @com.fasterxml.jackson.annotation.JsonProperty("algorithm_runtime_id")
        public String algorithmRuntimeId;
        @com.fasterxml.jackson.annotation.JsonProperty("view_ids")
        public List<String> viewIds;

        List<String> normalizedViewIds() {
            List<String> out = new ArrayList<>();
            if (viewIds != null) {
                for (String value : viewIds) {
                    if (value == null || value.isBlank()) {
                        throw new ContractViolationException("view_ids must be an array of non-empty strings", null);
                    }
                    out.add(value);
                }
            }
            return List.copyOf(out);
        }
    }
}
