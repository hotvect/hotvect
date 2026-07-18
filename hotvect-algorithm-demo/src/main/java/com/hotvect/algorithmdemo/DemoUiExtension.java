package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.algorithmserver.ActionMetadataLookup;
import com.hotvect.algorithmserver.AlgorithmServerApp;
import com.hotvect.algorithmserver.ContractViolationException;
import com.hotvect.algorithmserver.JsonFieldSupport;
import com.hotvect.algorithmserver.JsonInStringSupport;
import com.hotvect.algorithmserver.RequestBodyTooLargeException;
import com.hotvect.algorithmserver.ServerExtension;
import com.hotvect.algorithmserver.ValidationSupport;
import com.hotvect.utils.JsonUtils;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

final class DemoUiExtension implements ServerExtension {
    private static final Logger log = LoggerFactory.getLogger(DemoUiExtension.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final int MAX_EXAMPLES_LISTED = 100;

    private final Options opts;
    private final DemoSqliteCache sqliteCache;
    private final ExamplesRepository examples;
    private final ActionMetadataRepository actionMetadata;
    private final Map<DecodedExampleIdCacheKey, String> decodedExampleIdCache = new ConcurrentHashMap<>();

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
    public void configure(JavalinConfig config) {
        config.staticFiles.add(staticFileConfig -> {
            staticFileConfig.hostedPath = "/";
            staticFileConfig.directory = "/public";
            staticFileConfig.location = Location.CLASSPATH;
        });
    }

    @Override
    public void registerRoutes(AlgorithmServerApp app) {
        DemoComparisonService comparisonService = new DemoComparisonService(app, actionMetadata);

        // Keep both route families while older compare clients still call the unprefixed endpoints.
        get("/api/examples", ctx -> handleExamplesList(ctx, app));
        get("/api/demo/examples", ctx -> handleExamplesList(ctx, app));

        get("/api/examples/{example_index}", ctx -> handleExample(ctx, app, comparisonService));
        get("/api/demo/examples/{example_index}", ctx -> handleExample(ctx, app, comparisonService));

        get("/api/action-metadata/{action_id}", this::handleActionMetadata);
        get("/api/demo/action-metadata/{action_id}", this::handleActionMetadata);
        get("/api/demo/runtime-metadata", ctx -> handleRuntimeMetadata(ctx, app));

        post("/api/run", ctx -> handleRun(ctx, app));
        post("/api/demo/run", ctx -> handleRun(ctx, app));
        post("/api/demo/predict", ctx -> handleDemoPredict(ctx, app, comparisonService));
        post("/api/demo/compare", ctx -> handleDemoCompare(ctx, app, comparisonService));
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

        ObjectNode actionMetadataNode;
        JsonNode existingActionMetadataNode = root.get("action_metadata");
        if (existingActionMetadataNode instanceof ObjectNode existingObjectNode) {
            actionMetadataNode = existingObjectNode;
        } else {
            actionMetadataNode = root.putObject("action_metadata");
        }
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

    private void handleExamplesList(Context ctx, AlgorithmServerApp app) {
        try {
            int requestedLimit = parseIntOrDefault(ctx.queryParam("limit"), MAX_EXAMPLES_LISTED);
            int limit = Math.min(Math.max(requestedLimit, 1), MAX_EXAMPLES_LISTED);
            int count = Math.min(limit, examples.size());
            String algorithmRuntimeId = JsonFieldSupport.blankToNull(ctx.queryParam("algorithm_runtime_id"));
            List<ExamplesRepository.ExampleSummary> shown = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ExamplesRepository.ExampleRecord record = examples.getById(i);
                String exampleId = decodedExampleIdOrFallback(app, record, algorithmRuntimeId);
                shown.add(ExamplesRepository.ExampleSummary.of(record, exampleId));
            }
            ObjectNode node = OM.createObjectNode();
            node.put("count", examples.size());
            node.put("limit", limit);
            node.set("examples", OM.valueToTree(shown));
            ctx.status(HttpStatus.OK).json(node);
        } catch (ContractViolationException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(AlgorithmServerApp.error("Unhandled error", e.getMessage()));
        }
    }

    private void handleExample(Context ctx, AlgorithmServerApp app, DemoComparisonService comparisonService) {
        try {
            int exampleIndex = parseIntOrDefault(ctx.pathParam("example_index"), -1);
            ExamplesRepository.ExampleRecord record = examples.getById(exampleIndex);
            if (record == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(AlgorithmServerApp.error("Unknown example_index: " + ctx.pathParam("example_index"), null));
                return;
            }
            String algorithmRuntimeId = JsonFieldSupport.blankToNull(ctx.queryParam("algorithm_runtime_id"));
            ObjectNode node = OM.createObjectNode();
            node.put("example_index", record.id());
            node.put("source", record.source());
            String exampleId = decodedExampleIdOrFallback(app, record, algorithmRuntimeId);
            JsonFieldSupport.putStringOrNull(node, "example_id", exampleId);
            ObjectNode raw = ExamplesRepository.parseExampleObjectOrThrow(record.source(), record.rawJson()).deepCopy();
            DemoComparisonService.ExampleViewData viewData = comparisonService.exampleViewData(raw);
            node.set("shown_candidates", viewData.shownCandidates());
            node.set("views", viewData.views());
            node.set("default_view_ids", OM.valueToTree(viewData.defaultViewIds()));
            ObjectNode json = raw.deepCopy();
            JsonInStringSupport.injectVirtualJsonFields(json);
            node.set("json", json);
            ctx.status(HttpStatus.OK).json(node);
        } catch (ContractViolationException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(AlgorithmServerApp.error("Unhandled error", e.getMessage()));
        }
    }

    private void handleActionMetadata(Context ctx) {
        if (!actionMetadata.isEnabled()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(AlgorithmServerApp.error("Action metadata is disabled (start Demo UI with --action-metadata-path)", null));
            return;
        }
        String actionId = ctx.pathParam("action_id");
        try {
            JsonNode node = actionMetadata.getJsonOrFallbackIfEnabled(actionId);
            ctx.status(HttpStatus.OK).json(node);
        } catch (ContractViolationException e) {
            ctx.status(HttpStatus.NOT_FOUND).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(AlgorithmServerApp.error("Failed to load action metadata", e.getMessage()));
        }
    }

    private void handleRuntimeMetadata(Context ctx, AlgorithmServerApp app) {
        try {
            String algorithmRuntimeId = JsonFieldSupport.blankToNull(ctx.queryParam("algorithm_runtime_id"));
            ctx.status(HttpStatus.OK).json(app.buildRuntimeDetails(algorithmRuntimeId));
        } catch (ContractViolationException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Failed to load runtime metadata", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(
                    AlgorithmServerApp.error("Failed to load runtime metadata", e.getMessage()));
        }
    }

    private void handleRun(Context ctx, AlgorithmServerApp app) {
        try {
            RunRequest req = readRunRequest(ctx, app);
            DemoRunInput input = parseDemoRunInput(req);
            String expectedExampleId = JsonFieldSupport.nonEmptyStringField(input.exampleNode(), "example_id").orElse(null);
            ObjectNode response = app.runExample(input.exampleNode(), expectedExampleId, input.algorithmRuntimeId());
            if (input.exampleRecord() != null) {
                response.put("example_index", input.exampleRecord().id());
                response.put("example_source", input.exampleRecord().source());
            }
            ctx.status(HttpStatus.OK).json(response);
        } catch (RequestBodyTooLargeException e) {
            ctx.status(413).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (ExampleNotFoundException e) {
            ctx.status(HttpStatus.NOT_FOUND).json(AlgorithmServerApp.error(e.getMessage(), null));
        } catch (ContractViolationException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(AlgorithmServerApp.error("Unhandled error", e.getMessage()));
        }
    }

    private void handleDemoPredict(Context ctx, AlgorithmServerApp app, DemoComparisonService comparisonService) {
        try {
            RunRequest req = readRunRequest(ctx, app);
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
            ctx.status(HttpStatus.OK).json(response);
        } catch (RequestBodyTooLargeException e) {
            ctx.status(413).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (ExampleNotFoundException e) {
            ctx.status(HttpStatus.NOT_FOUND).json(AlgorithmServerApp.error(e.getMessage(), null));
        } catch (ContractViolationException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(AlgorithmServerApp.error("Unhandled error", e.getMessage()));
        }
    }

    private void handleDemoCompare(Context ctx, AlgorithmServerApp app, DemoComparisonService comparisonService) {
        try {
            RunRequest req = readRunRequest(ctx, app);
            DemoRunInput input = parseDemoRunInput(req);
            ObjectNode response = comparisonService.compare(input.exampleNode(), input.exampleRecord(), input.viewIds());
            ctx.status(HttpStatus.OK).json(response);
        } catch (RequestBodyTooLargeException e) {
            ctx.status(413).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (ExampleNotFoundException e) {
            ctx.status(HttpStatus.NOT_FOUND).json(AlgorithmServerApp.error(e.getMessage(), null));
        } catch (ContractViolationException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(AlgorithmServerApp.error(e.getMessage(), e.getDetails()));
        } catch (Exception e) {
            log.error("Unhandled error", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(AlgorithmServerApp.error("Unhandled error", e.getMessage()));
        }
    }

    private RunRequest readRunRequest(Context ctx, AlgorithmServerApp app) throws Exception {
        try {
            return OM.readValue(app.readRequestBodyBytes(ctx), RunRequest.class);
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
            JsonUtils.deepMergeJsonNodeWithArrayReplacement(exampleNode, overrideNode);
        }

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
            AlgorithmServerApp app,
            ExamplesRepository.ExampleRecord record,
            String algorithmRuntimeIdOrNull) {
        if (record == null) {
            return null;
        }
        AlgorithmServerApp.DecodedExampleId decodedExampleId = app.tryDecodeExampleIdOrNull(record.rawJson(), algorithmRuntimeIdOrNull);
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

    private static int parseIntOrDefault(String s, int defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
        // Legacy single-view alias kept for older compare clients that still post {"view_id": "..."}.
        @com.fasterxml.jackson.annotation.JsonProperty("view_id")
        public String viewId;

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
            if (out.isEmpty() && viewId != null && !viewId.isBlank()) {
                out.add(viewId);
            }
            return List.copyOf(out);
        }
    }
}
