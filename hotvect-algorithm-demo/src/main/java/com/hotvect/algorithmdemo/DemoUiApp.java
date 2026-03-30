package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.hotdeploy.StrictChildFirstClassLoader;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.utils.AlgorithmDefinitionReader;
import com.hotvect.utils.JsonUtils;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

public class DemoUiApp implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DemoUiApp.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final int MAX_EXAMPLES_LISTED = 100;

    private final Options opts;
    private final DemoSqliteCache sqliteCache;
    private final ExamplesRepository examples;
    private final ActionMetadataRepository actionMetadata;
    private final AlgorithmRuntime runtime;
    private final Javalin app;
    private final Map<Integer, String> decodedExampleIdCache = new ConcurrentHashMap<>();

    public DemoUiApp(Options opts) throws Exception {
        this.opts = Objects.requireNonNull(opts);

        require(opts.port >= 1 && opts.port <= 65535, "--port must be between 1 and 65535, got %s", opts.port);
        require(opts.algorithmJar.exists() && opts.algorithmJar.isFile(), "--algorithm-jar not found: %s", opts.algorithmJar.getAbsolutePath());
        require(opts.parameterPath.exists() && opts.parameterPath.isFile(), "--parameter-path not found: %s", opts.parameterPath.getAbsolutePath());
        if (opts.ui) {
            require(opts.sourcePath != null, "--source-path is required with --ui");
            require(opts.sourcePath.exists() && opts.sourcePath.isDirectory(), "--source-path must be a directory: %s", opts.sourcePath.getAbsolutePath());
        } else {
            require(opts.sourcePath == null, "--source-path is only supported with --ui");
            require(opts.actionMetadataPath == null, "--action-metadata-path is only supported with --ui");
            require(opts.demoSqlitePath == null, "--demo-sqlite-path is only supported with --ui");
        }
        if (opts.algorithmOverride != null) {
            require(opts.algorithmOverride.exists() && opts.algorithmOverride.isFile(), "--algorithm-override not found: %s", opts.algorithmOverride.getAbsolutePath());
        }
        if (opts.ui && opts.actionMetadataPath != null) {
            require(opts.actionMetadataPath.exists() && opts.actionMetadataPath.isDirectory(), "--action-metadata-path must be a directory: %s", opts.actionMetadataPath.getAbsolutePath());
        }
        require(opts.maxRequestMiB >= 1 && opts.maxRequestMiB <= 4096, "--max-request-mib must be between 1 and 4096, got %s", opts.maxRequestMiB);

        this.examples = opts.ui ? ExamplesRepository.loadFromDirectory(opts.sourcePath, MAX_EXAMPLES_LISTED) : ExamplesRepository.empty();
        this.sqliteCache = opts.ui ? DemoSqliteCache.openOrBuild(opts) : null;
        this.actionMetadata = sqliteCache == null ? ActionMetadataRepository.empty() : sqliteCache.actionMetadata();
        this.runtime = new AlgorithmRuntime(opts.algorithmJar, opts.algorithmName, opts.algorithmOverride, opts.parameterPath);

        this.app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            // Javalin 7 defaults to 1MB, which is too small for many real-world Hotvect examples.
            config.http.maxRequestSize = opts.maxRequestMiB * 1024L * 1024L;
            if (opts.ui) {
                config.staticFiles.add(staticFileConfig -> {
                    staticFileConfig.hostedPath = "/";
                    staticFileConfig.directory = "/public";
                    staticFileConfig.location = Location.CLASSPATH;
                });
            }
            config.routes.apiBuilder(this::registerRoutes);
        });
    }

    public void start() {
        app.start(opts.host, opts.port);
        String baseUrl = "http://" + (Objects.equals(opts.host, "0.0.0.0") ? "127.0.0.1" : opts.host) + ":" + opts.port;
        log.info("Algorithm server started at {} (ui={})", baseUrl, opts.ui);
        if (sqliteCache != null) {
            log.info("SQLite cache: {}", sqliteCache.dbPath());
        }
        if (opts.ui) {
            log.info("Examples loaded: {}", examples.size());
            log.info("Action metadata loaded: {}", actionMetadata.size());
        }
    }

    private void registerRoutes() {
        get("/health", ctx -> {
            ObjectNode node = OM.createObjectNode();
            node.put("status", "ok");
            ctx.status(HttpStatus.OK).json(node);
        });

        post("/predict", ctx -> {
            ObjectNode exampleNode;
            try {
                JsonNode body = OM.readTree(ctx.bodyAsBytes());
                if (!(body instanceof ObjectNode objectNode)) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(error("Request body must be a JSON object", null));
                    return;
                }
                exampleNode = objectNode.deepCopy();
            } catch (HttpResponseException e) {
                ctx.status(e.getStatus()).json(error("Invalid request body", e.getMessage()));
                return;
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("Invalid JSON request body", e.getMessage()));
                return;
            }

            try {
                ObjectNode response = runtime.runRawExampleJson(exampleNode, actionMetadata);
                ctx.status(HttpStatus.OK).json(response);
            } catch (ContractViolationException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error(e.getMessage(), e.getDetails()));
            } catch (Exception e) {
                log.error("Unhandled error", e);
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(error("Unhandled error", e.getMessage()));
            }
        });

        get("/api/health", ctx -> {
            ObjectNode node = OM.createObjectNode();
            node.put("status", "ok");
            ctx.status(HttpStatus.OK).json(node);
        });

        get("/api/metadata", ctx -> ctx.status(HttpStatus.OK).json(buildMetadata()));

        get("/api/config", ctx -> {
            // Backward-compatible alias for /api/metadata.
            ctx.status(HttpStatus.OK).json(buildMetadata());
        });

        if (!opts.ui) {
            return;
        }

        get("/api/examples", ctx -> {
            int requestedLimit = parseIntOrDefault(ctx.queryParam("limit"), MAX_EXAMPLES_LISTED);
            int limit = Math.min(Math.max(requestedLimit, 1), MAX_EXAMPLES_LISTED);
            int count = Math.min(limit, examples.size());
            List<ExamplesRepository.ExampleSummary> shown = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ExamplesRepository.ExampleRecord record = examples.getById(i);
                String exampleId = decodedExampleIdOrFallback(record);
                shown.add(ExamplesRepository.ExampleSummary.of(record, exampleId));
            }
            ObjectNode node = OM.createObjectNode();
            node.put("count", examples.size());
            node.put("limit", limit);
            node.set("examples", OM.valueToTree(shown));
            ctx.json(node);
        });

        get("/api/examples/{example_index}", ctx -> {
            int exampleIndex = parseIntOrDefault(ctx.pathParam("example_index"), -1);
            ExamplesRepository.ExampleRecord record = examples.getById(exampleIndex);
            if (record == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(error("Unknown example_index: " + ctx.pathParam("example_index"), null));
                return;
            }
            ObjectNode node = OM.createObjectNode();
            node.put("example_index", record.id());
            node.put("source", record.source());
            String exampleId = decodedExampleIdOrFallback(record);
            if (exampleId == null) {
                node.putNull("example_id");
            } else {
                node.put("example_id", exampleId);
            }
            ObjectNode raw = ExamplesRepository.parseExampleObjectOrThrow(record.source(), record.rawJson()).deepCopy();
            node.set("shown_candidates", buildShownCandidates(raw, actionMetadata));
            ObjectNode json = raw.deepCopy();
            JsonInStringSupport.injectVirtualJsonFields(json);
            node.set("json", json);
            ctx.json(node);
        });

        get("/api/action-metadata/{action_id}", ctx -> {
            if (!actionMetadata.isEnabled()) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("Action metadata is disabled (start Demo UI with --action-metadata-path)", null));
                return;
            }
            String actionId = ctx.pathParam("action_id");
            try {
                JsonNode node = actionMetadata.requireJsonIfEnabled(actionId);
                ctx.status(HttpStatus.OK).json(node);
            } catch (ContractViolationException e) {
                ctx.status(HttpStatus.NOT_FOUND).json(error(e.getMessage(), e.getDetails()));
            } catch (Exception e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(error("Failed to load action metadata", e.getMessage()));
            }
        });

        post("/api/run", ctx -> {
            RunRequest req;
            try {
                req = OM.readValue(ctx.bodyAsBytes(), RunRequest.class);
            } catch (HttpResponseException e) {
                ctx.status(e.getStatus()).json(error("Invalid request body", e.getMessage()));
                return;
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("Invalid JSON request body", e.getMessage()));
                return;
            }

            if (req.exampleIndex == null) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("Missing required field: example_index", null));
                return;
            }

            ExamplesRepository.ExampleRecord exampleRecord = examples.getById(req.exampleIndex);
            if (exampleRecord == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(error("Unknown example_index: " + req.exampleIndex, null));
                return;
            }

            if (req.exampleJson != null && req.overrideJson != null && !req.overrideJson.isBlank()) {
                ctx.status(HttpStatus.BAD_REQUEST).json(
                        error("Cannot set both example_json and override_json; edit one or the other", null)
                );
                return;
            }

            ObjectNode exampleNode;
            if (req.exampleJson != null) {
                if (!req.exampleJson.isObject()) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(
                            error("example_json must be a JSON object", "got " + req.exampleJson.getNodeType().name().toLowerCase())
                    );
                    return;
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
                    ctx.status(HttpStatus.BAD_REQUEST).json(error("override_json is not valid JSON", e.getOriginalMessage()));
                    return;
                }
                if (!overrideNode.isObject()) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(
                            error("override_json must be a JSON object", "got " + overrideNode.getNodeType().name().toLowerCase())
                    );
                    return;
                }
                JsonUtils.deepMergeJsonNodeWithArrayReplacement(exampleNode, overrideNode);
            }

            try {
                JsonInStringSupport.collapseVirtualJsonFields(exampleNode);
                String expectedExampleId = nonEmptyStringField(exampleNode, "example_id").orElse(null);
                ObjectNode response = runtime.runExample(exampleNode, expectedExampleId, actionMetadata);
                response.put("example_index", exampleRecord.id());
                response.put("example_source", exampleRecord.source());
                ctx.status(HttpStatus.OK).json(response);
            } catch (ContractViolationException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error(e.getMessage(), e.getDetails()));
            } catch (Exception e) {
                log.error("Unhandled error", e);
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(error("Unhandled error", e.getMessage()));
            }
        });
    }

    private ObjectNode buildMetadata() {
        ObjectNode root = OM.createObjectNode();

        root.put("algorithm_jar", opts.algorithmJar.getAbsolutePath());
        root.put("algorithm_name", opts.algorithmName);
        root.put("parameter_path", opts.parameterPath.getAbsolutePath());
        if (opts.sourcePath == null) {
            root.putNull("source_path");
        } else {
            root.put("source_path", opts.sourcePath.getAbsolutePath());
        }
        if (sqliteCache == null) {
            root.putNull("demo_sqlite_path");
            root.put("demo_sqlite_built_now", false);
        } else {
            root.put("demo_sqlite_path", sqliteCache.dbPath().toAbsolutePath().toString());
            root.put("demo_sqlite_built_now", sqliteCache.builtNow());
        }
        root.put("ui_enabled", opts.ui);
        root.put("json_in_string_auto", true);

        ObjectNode algorithmNode = root.putObject("algorithm");
        AlgorithmDefinition def = runtime.getAlgorithmDefinition();
        algorithmNode.put("name", def.algorithmId().algorithmName());
        algorithmNode.put("version", def.algorithmId().algorithmVersion());
        putTextOrNull(algorithmNode, "hyperparameter_version", def.rawAlgorithmDefinition(), "hyperparameter_version");
        putTextOrNull(algorithmNode, "git_describe", def.rawAlgorithmDefinition(), "git_describe");
        String hotvectVersion = runtime.getHotvectVersionFromMavenOrNull();
        if (hotvectVersion == null || hotvectVersion.isBlank()) {
            algorithmNode.putNull("hotvect_version");
        } else {
            algorithmNode.put("hotvect_version", hotvectVersion);
        }

        AlgorithmParameterMetadata params = runtime.getAlgorithmParameterMetadataOrNull();
        if (params == null) {
            root.putNull("parameters");
        } else {
            ObjectNode parametersNode = root.putObject("parameters");
            parametersNode.put("algorithm_name", params.algorithmId().algorithmName());
            parametersNode.put("algorithm_version", params.algorithmId().algorithmVersion());
            parametersNode.put("parameter_id", params.parameterId());
            parametersNode.put("ran_at", params.ranAt().toString());
            if (params.lastTestTime().isPresent()) {
                parametersNode.put("last_test_time", params.lastTestTime().get().toString());
            } else {
                parametersNode.putNull("last_test_time");
            }
        }

        root.put("examples_count", examples.size());
        ObjectNode actionMetadataNode = root.putObject("action_metadata");
        actionMetadataNode.put("enabled", actionMetadata.isEnabled());
        actionMetadataNode.put("count", actionMetadata.size());
        if (opts.actionMetadataPath == null) {
            actionMetadataNode.putNull("path");
        } else {
            actionMetadataNode.put("path", opts.actionMetadataPath.getAbsolutePath());
        }

        return root;
    }

    private static void putTextOrNull(ObjectNode dest, String outKey, JsonNode src, String srcKey) {
        if (src == null) {
            dest.putNull(outKey);
            return;
        }
        JsonNode n = src.get(srcKey);
        if (n == null || n.isNull()) {
            dest.putNull(outKey);
            return;
        }
        String s = n.asText();
        if (s == null || s.isBlank()) {
            dest.putNull(outKey);
        } else {
            dest.put(outKey, s);
        }
    }

    private static ArrayNode buildShownCandidates(ObjectNode exampleJson, ActionMetadataRepository actionMetadata) {
        Objects.requireNonNull(exampleJson);
        Objects.requireNonNull(actionMetadata);

        ArrayNode out = OM.createArrayNode();
        JsonNode candidatesNode = exampleJson.at("/relevance_request/candidates");
        if (candidatesNode == null || !candidatesNode.isArray()) {
            return out;
        }

        record Candidate(
                String actionId,
                Double originalRank,
                Double overallPosition,
                Double filteredPosition,
                Double shownPosition,
                Double score,
                Double pageNumber,
                Double indexInPage,
                Double impressionCount,
                int originalIndex
        ) {
        }

        List<Candidate> candidates = new ArrayList<>();
        Set<String> actionIds = new LinkedHashSet<>();
        int idx = 0;
        for (JsonNode candidateNode : candidatesNode) {
            if (candidateNode == null || !candidateNode.isObject()) {
                idx++;
                continue;
            }
            String actionId = textOrNull(candidateNode.get("id"));
            if (actionId == null || actionId.isBlank()) {
                idx++;
                continue;
            }

            JsonNode attribution = candidateNode.get("attribution");
            Double overallPosition = numberOrNull(attribution, "overall_position");
            Double filteredPosition = numberOrNull(attribution, "filtered_position");
            Double pageNumber = numberOrNull(attribution, "page_number");
            Double indexInPage = numberOrNull(attribution, "index_in_page");
            Double impressionCount = numberOrNull(attribution, "impression_count");
            Double originalRank = rankOrNull(candidateNode, attribution);
            Double score = numberOrNull(attribution, "original_score");
            Double shownPosition = firstFiniteOrDefault(overallPosition, filteredPosition, null, null);

            candidates.add(new Candidate(
                    actionId,
                    originalRank,
                    overallPosition,
                    filteredPosition,
                    shownPosition,
                    score,
                    pageNumber,
                    indexInPage,
                    impressionCount,
                    idx
            ));
            actionIds.add(actionId);
            idx++;
        }

        Comparator<Candidate> byOriginalRank = Comparator
                .comparingDouble((Candidate c) -> firstFiniteOrDefault(c.shownPosition, null, Double.POSITIVE_INFINITY))
                .thenComparingInt(Candidate::originalIndex);
        candidates.sort(byOriginalRank);

        Map<String, ActionMetadataRepository.ActionMetadata> metas = actionMetadata.getAllIfEnabled(actionIds);
        for (Candidate c : candidates) {
            ObjectNode n = OM.createObjectNode();
            n.put("action_id", c.actionId);
            putNumberOrNull(n, "original_rank", c.originalRank);
            putNumberOrNull(n, "shown_position", c.shownPosition);
            putNumberOrNull(n, "overall_position", c.overallPosition);
            putNumberOrNull(n, "filtered_position", c.filteredPosition);
            putNumberOrNull(n, "score", c.score);
            putNumberOrNull(n, "page_number", c.pageNumber);
            putNumberOrNull(n, "index_in_page", c.indexInPage);
            putNumberOrNull(n, "impression_count", c.impressionCount);

            ActionMetadataRepository.ActionMetadata meta = metas.get(c.actionId);
            if (meta == null) {
                n.putNull("action_name");
                n.putNull("action_image_url");
            } else {
                n.put("action_name", meta.actionName());
                n.put("action_image_url", meta.actionImageUrl());
            }
            out.add(n);
        }
        return out;
    }

    private static Double rankOrNull(JsonNode candidateNode, JsonNode attributionNode) {
        Double rank = numberOrNull(attributionNode, "original_rank");
        if (rank != null) {
            return rank;
        }
        return numberOrNull(attributionNode, "rank");
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s = node.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static Double numberOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode n = node.get(field);
        if (n == null || n.isNull() || !n.isNumber()) {
            return null;
        }
        double v = n.asDouble();
        if (!Double.isFinite(v)) {
            return null;
        }
        return v;
    }

    private static void putNumberOrNull(ObjectNode dest, String field, Double value) {
        if (value == null || !Double.isFinite(value)) {
            dest.putNull(field);
            return;
        }
        dest.put(field, value);
    }

    private static double firstFiniteOrDefault(Double a, Double b, double defaultValue) {
        if (a != null && Double.isFinite(a)) {
            return a;
        }
        return defaultValue;
    }

    private static Double firstFiniteOrDefault(Double a, Double b, Double c, Double defaultValue) {
        if (a != null && Double.isFinite(a)) {
            return a;
        }
        if (b != null && Double.isFinite(b)) {
            return b;
        }
        if (c != null && Double.isFinite(c)) {
            return c;
        }
        return defaultValue;
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

    private static Optional<String> nonEmptyStringField(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    private static ObjectNode error(String message, String details) {
        ObjectNode root = OM.createObjectNode();
        ObjectNode err = root.putObject("error");
        err.put("message", message);
        if (details == null) {
            err.putNull("details");
        } else {
            err.put("details", details);
        }
        return root;
    }

    private String decodedExampleIdOrFallback(ExamplesRepository.ExampleRecord record) {
        if (record == null) {
            return null;
        }
        return decodedExampleIdCache.computeIfAbsent(record.id(), _ignored -> {
            String decoded = runtime.tryDecodeExampleIdOrNull(record.rawJson());
            if (decoded != null && !decoded.isBlank()) {
                return decoded;
            }
            return record.exampleId();
        });
    }

    @Override
    public void close() {
        if (sqliteCache != null) {
            sqliteCache.close();
        }
        runtime.close();
        app.stop();
    }

    private static final class RunRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("example_index")
        public Integer exampleIndex;
        @com.fasterxml.jackson.annotation.JsonProperty("override_json")
        public String overrideJson;
        @com.fasterxml.jackson.annotation.JsonProperty("example_json")
        public JsonNode exampleJson;
    }

    static final class AlgorithmRuntime implements AutoCloseable {
        private final File algorithmJar;
        private final File parameterPath;
        private final AlgorithmInstanceFactory algorithmInstanceFactory;
        private final AlgorithmDefinition algorithmDefinition;
        private final AlgorithmInstance<?> algorithmInstance;
        private final ExampleDecoder<?> exampleDecoder;

        AlgorithmRuntime(File algorithmJar, String algorithmName, File algorithmOverride, File parameterPath) throws Exception {
            this.algorithmJar = algorithmJar;
            this.parameterPath = parameterPath;
            var classLoader = createAlgorithmClassLoader(algorithmJar);
            // Demo UI is for local debugging; allow parameter zips whose embedded algorithm_version does not
            // exactly match the algorithm definition version (strict version check disabled).
            this.algorithmInstanceFactory = new AlgorithmInstanceFactory(
                    classLoader,
                    ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE),
                    false
            );
            AlgorithmDefinition baseDefinition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, classLoader);
            this.algorithmDefinition = applyAlgorithmOverride(baseDefinition, algorithmOverride);

            this.algorithmInstance = algorithmInstanceFactory.load(this.algorithmDefinition, parameterPath, Map.of());

            if (this.algorithmDefinition.decoderFactoryName() == null) {
                throw new ContractViolationException("Algorithm definition missing decoder_factory_classname", null);
            }
            Object decoderFactory = classLoader.loadClass(this.algorithmDefinition.decoderFactoryName())
                    .getDeclaredConstructor()
                    .newInstance();
            // Decoder factory is ExampleDecoderFactory<EXAMPLE>, which is Function<Optional<JsonNode>, ExampleDecoder<EXAMPLE>>
            this.exampleDecoder = ((java.util.function.Function<Optional<JsonNode>, ExampleDecoder<?>>) decoderFactory)
                    .apply(this.algorithmDefinition.testDecoderParameter());
        }

        AlgorithmDefinition getAlgorithmDefinition() {
            return algorithmDefinition;
        }

        AlgorithmParameterMetadata getAlgorithmParameterMetadataOrNull() {
            return algorithmInstance.algorithmParameterMetadata();
        }

        String getHotvectVersionFromMavenOrNull() {
            if (algorithmJar == null || !algorithmJar.exists() || !algorithmJar.isFile()) {
                return null;
            }
            SortedSet<String> versions = new TreeSet<>();
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(algorithmJar)) {
                addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-core", versions);
                addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-tensorflow", versions);
                addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-catboost", versions);
            } catch (Exception ignored) {
                return null;
            }
            if (versions.isEmpty()) {
                return null;
            }
            return String.join(", ", versions);
        }

        private static void addMavenPomPropertiesVersionIfPresent(java.util.zip.ZipFile zf, String groupId, String artifactId, Set<String> versions)
                throws IOException {
            String entry = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
            var ze = zf.getEntry(entry);
            if (ze == null) {
                return;
            }
            try (var in = zf.getInputStream(ze)) {
                java.util.Properties props = new java.util.Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) {
                    versions.add(v.trim());
                }
            }
        }

        ObjectNode runExample(ObjectNode caseNode, String expectedExampleIdOrNull, ActionMetadataRepository metadataRepository) throws Exception {
            String jsonString = OM.writeValueAsString(caseNode);
            List<?> decoded = exampleDecoder.apply(jsonString);
            if (decoded == null || decoded.isEmpty()) {
                throw new ContractViolationException("Decoder produced no examples for case", expectedExampleIdOrNull == null ? null : "expected example_id=" + expectedExampleIdOrNull);
            }
            if (decoded.size() != 1) {
                throw new ContractViolationException("Decoder must produce exactly 1 example for a case; got " + decoded.size(), null);
            }

            Example<OfflineRequest<?>, ?> ex = (Example<OfflineRequest<?>, ?>) decoded.getFirst();
            String decodedExampleId = requireNonEmptyExampleId(ex.exampleId());
            if (expectedExampleIdOrNull != null && !Objects.equals(decodedExampleId, expectedExampleIdOrNull)) {
                throw new ContractViolationException(
                        "Decoded example_id does not match case JSON example_id",
                        "case=" + expectedExampleIdOrNull + ", decoded=" + decodedExampleId
                );
            }

            Object algo = algorithmInstance.algorithm();

            if (algo instanceof Ranker<?, ?>) {
                return runRanker(decodedExampleId, (Ranker) algo, (RankingRequest) ex.request(), metadataRepository);
            }

            if (algo instanceof TopK<?, ?>) {
                return runTopK(decodedExampleId, (TopK) algo, (TopKRequest) ex.request(), metadataRepository);
            }

            throw new ContractViolationException("Unsupported algorithm type: " + algo.getClass().getCanonicalName(), null);
        }

        String tryDecodeExampleIdOrNull(String caseJsonString) {
            try {
                List<?> decoded = exampleDecoder.apply(caseJsonString);
                if (decoded == null || decoded.isEmpty()) {
                    return null;
                }
                if (decoded.size() != 1) {
                    return null;
                }
                Example<OfflineRequest<?>, ?> ex = (Example<OfflineRequest<?>, ?>) decoded.getFirst();
                String exampleId = ex.exampleId();
                if (exampleId == null || exampleId.isBlank()) {
                    return null;
                }
                return exampleId;
            } catch (Exception ignored) {
                return null;
            }
        }

        ObjectNode runRawExampleJson(ObjectNode exampleNode, ActionMetadataRepository metadataRepository) throws Exception {
            String expectedExampleId = nonEmptyStringField(exampleNode, "example_id").orElse(null);
            JsonInStringSupport.collapseVirtualJsonFields(exampleNode);
            return runExample(exampleNode, expectedExampleId, metadataRepository);
        }

        private ObjectNode runRanker(
                String exampleId,
                Ranker ranker,
                RankingRequest request,
                ActionMetadataRepository metadataRepository
        ) {
            RankingResponse<?> response = ranker.rank(request);
            List<RankingDecision<?>> decisions = (List<RankingDecision<?>>) response.decisions();
            List<String> actionIds = decisions.stream().map(d -> requireNonEmptyActionId(d.actionId())).distinct().toList();
            Map<String, ActionMetadataRepository.ActionMetadata> metaById = metadataRepository.getAllIfEnabled(actionIds);

            ObjectNode root = OM.createObjectNode();
            root.put("type", "ranker");
            root.put("example_id", exampleId);
            root.set("additional_properties", OM.valueToTree(response.additionalProperties()));

            var decisionsNode = root.putArray("decisions");
            for (int rank = 0; rank < decisions.size(); rank++) {
                RankingDecision<?> decision = decisions.get(rank);
                String actionId = requireNonEmptyActionId(decision.actionId());
                ActionMetadataRepository.ActionMetadata meta = metaById.get(actionId);

                ObjectNode d = OM.createObjectNode();
                d.put("rank", rank);
                d.put("action_id", actionId);
                if (meta == null) {
                    d.putNull("action_name");
                    d.putNull("action_image_url");
                } else {
                    d.put("action_name", meta.actionName());
                    d.put("action_image_url", meta.actionImageUrl());
                }
                if (decision.score() != null) {
                    d.put("score", decision.score());
                }
                if (decision.probability() != null) {
                    d.put("probability", decision.probability());
                }
                d.set("additional_properties", OM.valueToTree(decision.additionalProperties()));
                decisionsNode.add(d);
            }

            return root;
        }

        private ObjectNode runTopK(
                String exampleId,
                TopK topK,
                TopKRequest request,
                ActionMetadataRepository metadataRepository
        ) {
            TopKResponse<?> response = topK.apply(request);
            List<TopKDecision<?>> decisions = (List<TopKDecision<?>>) response.decisions();
            List<String> actionIds = decisions.stream().map(d -> requireNonEmptyActionId(d.actionId())).distinct().toList();
            Map<String, ActionMetadataRepository.ActionMetadata> metaById = metadataRepository.getAllIfEnabled(actionIds);

            ObjectNode root = OM.createObjectNode();
            root.put("type", response instanceof ThemedTopKResponse ? "themed_topk" : "topk");
            root.put("example_id", exampleId);
            root.set("additional_properties", OM.valueToTree(response.additionalProperties()));

            if (response instanceof ThemedTopKResponse<?> themed) {
                root.put("action_list_id", themed.getActionListId());
                root.set("action_list_metadata", OM.valueToTree(themed.getActionListMetadata()));
            }

            var decisionsNode = root.putArray("decisions");
            for (int rank = 0; rank < decisions.size(); rank++) {
                TopKDecision<?> decision = decisions.get(rank);
                String actionId = requireNonEmptyActionId(decision.actionId());
                ActionMetadataRepository.ActionMetadata meta = metaById.get(actionId);

                ObjectNode d = OM.createObjectNode();
                d.put("rank", rank);
                d.put("action_id", actionId);
                if (meta == null) {
                    d.putNull("action_name");
                    d.putNull("action_image_url");
                } else {
                    d.put("action_name", meta.actionName());
                    d.put("action_image_url", meta.actionImageUrl());
                }
                if (decision.score() != null) {
                    d.put("score", decision.score());
                }
                if (decision.probability() != null) {
                    d.put("probability", decision.probability());
                }
                d.set("additional_properties", OM.valueToTree(decision.additionalProperties()));
                decisionsNode.add(d);
            }

            return root;
        }

        private static String requireNonEmptyActionId(String actionId) {
            if (actionId == null || actionId.isBlank()) {
                throw new ContractViolationException("Algorithm response is missing required action_id", null);
            }
            return actionId;
        }

        private static String requireNonEmptyExampleId(String exampleId) {
            if (exampleId == null || exampleId.isBlank()) {
                throw new ContractViolationException("Decoded example_id is missing/blank", null);
            }
            return exampleId;
        }

        private static AlgorithmDefinition applyAlgorithmOverride(AlgorithmDefinition baseDefinition, File overrideFile) throws IOException {
            if (overrideFile == null) {
                return baseDefinition;
            }
            JsonNode overrideNode = OM.readTree(overrideFile);
            if (!overrideNode.isObject()) {
                throw new ContractViolationException("--algorithm-override must be a JSON object", overrideFile.getAbsolutePath());
            }
            JsonNode merged = baseDefinition.rawAlgorithmDefinition().deepCopy();
            JsonUtils.deepMergeJsonNodeWithArrayReplacement(merged, overrideNode);
            try {
                return new AlgorithmDefinitionReader().parse(merged);
            } catch (IOException e) {
                throw new IOException("Failed to parse merged algorithm definition after applying --algorithm-override", e);
            }
        }

        private static ClassLoader createAlgorithmClassLoader(File algorithmJar) throws IOException {
            var jarUrl = algorithmJar.toURI().toURL();
            Set<String> required = Set.of("com.hotvect.core.transform.Namespaces");
            return new StrictChildFirstClassLoader(new java.net.URL[]{jarUrl}, Thread.currentThread().getContextClassLoader(), required);
        }

        @Override
        public void close() {
            try {
                algorithmInstance.close();
            } catch (Exception e) {
                log.warn("Failed to close algorithm instance", e);
            }
        }
    }

    private static void require(boolean condition, String messageTemplate, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException(String.format(messageTemplate, args));
        }
    }
}
