package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.AlgorithmRuntimeIdentity;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

public class AlgorithmServerApp implements AutoCloseable {
    static final long MAX_BUFFERED_REQUEST_MIB = 512;
    private static final Logger log = LoggerFactory.getLogger(AlgorithmServerApp.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final ServerOptions opts;
    private final ActionMetadataLookup actionMetadata;
    private final List<ServerExtension> extensions;
    private final AlgorithmRuntimeProvider runtimeProvider;
    private final Javalin app;

    public static int runUntilInterrupted(ServerOptions opts) throws Exception {
        return runUntilInterrupted(opts, ActionMetadataLookup.empty(), List.of());
    }

    public static int runUntilInterrupted(
            ServerOptions opts,
            ActionMetadataLookup actionMetadata,
            List<ServerExtension> extensions) throws Exception {
        AlgorithmServerApp app = null;
        try {
            app = new AlgorithmServerApp(opts, actionMetadata, extensions);
            try (AlgorithmServerApp runningApp = app) {
                runningApp.start();
                Thread.sleep(Long.MAX_VALUE);
                return 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            if (app == null) {
                closeStartupResources(actionMetadata, extensions, e);
            }
            throw e;
        }
    }

    public AlgorithmServerApp(ServerOptions opts) throws Exception {
        this(opts, ActionMetadataLookup.empty(), List.of());
    }

    public AlgorithmServerApp(
            ServerOptions opts,
            ActionMetadataLookup actionMetadata,
            List<ServerExtension> extensions) throws Exception {
        this(opts, actionMetadata, extensions, null);
    }

    AlgorithmServerApp(
            ServerOptions opts,
            ActionMetadataLookup actionMetadata,
            List<ServerExtension> extensions,
            AlgorithmRuntimeProvider runtimeProvider) throws Exception {
        this.opts = Objects.requireNonNull(opts);
        this.actionMetadata = Objects.requireNonNull(actionMetadata);
        this.extensions = List.copyOf(Objects.requireNonNull(extensions));

        ValidationSupport.requireArgument(
                opts.port >= 1 && opts.port <= 65535,
                "--port must be between 1 and 65535, got %s",
                opts.port);
        ValidationSupport.requireArgument(
                opts.maxRequestMiB >= 1 && opts.maxRequestMiB <= MAX_BUFFERED_REQUEST_MIB,
                "--max-request-mib must be between 1 and %s, got %s",
                MAX_BUFFERED_REQUEST_MIB,
                opts.maxRequestMiB);

        this.runtimeProvider = runtimeProvider == null
                ? createRuntimeProvider(opts)
                : runtimeProvider;
        this.app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            // Keep framework-level request limits effectively disabled so we can return the same
            // structured JSON 413 response regardless of Content-Length correctness.
            config.http.maxRequestSize = Long.MAX_VALUE;
            for (ServerExtension extension : this.extensions) {
                extension.configure(config);
            }
            config.routes.apiBuilder(this::registerRoutes);
        });
    }

    private static AlgorithmRuntimeProvider createRuntimeProvider(ServerOptions opts) throws Exception {
        AlgorithmRuntimeProviders.validateOptions(opts);
        if (opts.algorithmOverride != null) {
            ValidationSupport.requireArgument(
                    opts.algorithmOverride.exists() && opts.algorithmOverride.isFile(),
                    "--algorithm-override not found: %s",
                    opts.algorithmOverride.getAbsolutePath());
        }
        return AlgorithmRuntimeProviders.create(opts);
    }

    private static void closeStartupResources(
            ActionMetadataLookup actionMetadata,
            List<ServerExtension> extensions,
            Exception failure) {
        closeExtensionsAndMetadata(extensions, actionMetadata, failure);
    }

    public void start() {
        app.start(opts.host, opts.port);
        String baseUrl = "http://" + (Objects.equals(opts.host, "0.0.0.0") ? "127.0.0.1" : opts.host) + ":" + opts.port;
        log.info("Algorithm server started at {}", baseUrl);
        for (ServerExtension extension : extensions) {
            extension.onStarted(baseUrl);
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
            String algorithmRuntimeId = JsonFieldSupport.blankToNull(ctx.queryParam("algorithm_runtime_id"));
            try {
                JsonNode body = OM.readTree(readRequestBodyBytes(ctx));
                if (!(body instanceof ObjectNode objectNode)) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(error("Request body must be a JSON object", null));
                    return;
                }
                exampleNode = objectNode.deepCopy();
            } catch (RequestBodyTooLargeException e) {
                ctx.status(413).json(error(e.getMessage(), e.getDetails()));
                return;
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("Invalid JSON request body", e.getMessage()));
                return;
            }

            try {
                ctx.status(HttpStatus.OK).json(runRawExampleJson(exampleNode, algorithmRuntimeId));
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
            ctx.status(HttpStatus.OK).json(buildMetadata());
        });

        for (ServerExtension extension : extensions) {
            extension.registerRoutes(this);
        }
    }

    public byte[] readRequestBodyBytes(Context ctx) throws IOException {
        return readRequestBodyBytes(
                ctx.req().getInputStream(),
                ctx.req().getContentLengthLong(),
                maxRequestBytes(),
                opts.maxRequestMiB
        );
    }

    public ObjectNode runRawExampleJson(ObjectNode exampleNode) throws Exception {
        return runRawExampleJson(exampleNode, null);
    }

    public ObjectNode runRawExampleJson(ObjectNode exampleNode, String algorithmRuntimeIdOrNull) throws Exception {
        RuntimeSelection selection = runtimeProvider.selectRuntime(JsonFieldSupport.blankToNull(algorithmRuntimeIdOrNull));
        ObjectNode response = selection.runtime().runRawExampleJson(exampleNode, actionMetadata);
        addServingIdentity(response, selection);
        return response;
    }

    public ObjectNode runExample(ObjectNode exampleNode, String expectedExampleIdOrNull) throws Exception {
        return runExample(exampleNode, expectedExampleIdOrNull, null);
    }

    public ObjectNode runExample(ObjectNode exampleNode, String expectedExampleIdOrNull, String algorithmRuntimeIdOrNull) throws Exception {
        RuntimeSelection selection = runtimeProvider.selectRuntime(JsonFieldSupport.blankToNull(algorithmRuntimeIdOrNull));
        ObjectNode response = selection.runtime().runExample(exampleNode, expectedExampleIdOrNull, actionMetadata);
        addServingIdentity(response, selection);
        return response;
    }

    public DecodedExampleId tryDecodeExampleIdOrNull(String rawJson) {
        return tryDecodeExampleIdOrNull(rawJson, null);
    }

    public DecodedExampleId tryDecodeExampleIdOrNull(String rawJson, String algorithmRuntimeIdOrNull) {
        RuntimeSelection selection = runtimeProvider.selectRuntime(JsonFieldSupport.blankToNull(algorithmRuntimeIdOrNull));
        String decoded = selection.runtime().tryDecodeExampleIdOrNull(rawJson);
        return new DecodedExampleId(selection.runtime().identity().algorithmRuntimeId(), decoded);
    }

    public List<DecodedOnlineCandidate> decodeOnlineCandidates(String rawJson) {
        return decodeOnlineCandidates(rawJson, null);
    }

    public List<DecodedOnlineCandidate> decodeOnlineCandidates(String rawJson, String algorithmRuntimeIdOrNull) {
        RuntimeSelection selection = runtimeProvider.selectRuntime(JsonFieldSupport.blankToNull(algorithmRuntimeIdOrNull));
        return selection.runtime().decodeOnlineCandidates(rawJson);
    }

    public ActionMetadataLookup actionMetadata() {
        return actionMetadata;
    }

    public ObjectMapper objectMapper() {
        return OM;
    }

    public static ObjectNode error(String message, String details) {
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

    public ObjectNode buildMetadata() {
        ObjectNode root = OM.createObjectNode();
        RuntimeSelection selection = runtimeProvider.selectRuntime();
        AlgorithmRuntime runtime = selection.runtime();

        addRuntimeMetadata(root, runtime);
        runtimeProvider.addMetadata(root, selection);

        ObjectNode actionMetadataNode = root.putObject("action_metadata");
        actionMetadataNode.put("enabled", actionMetadata.isEnabled());
        actionMetadataNode.put("count", actionMetadata.size());
        for (ServerExtension extension : extensions) {
            extension.addMetadata(root);
        }
        return root;
    }

    public ObjectNode buildRuntimeDetails(String algorithmRuntimeIdOrNull) throws IOException {
        RuntimeSelection selection = runtimeProvider.selectRuntime(JsonFieldSupport.blankToNull(algorithmRuntimeIdOrNull));
        AlgorithmRuntime runtime = selection.runtime();
        ObjectNode root = OM.createObjectNode();
        root.put("algorithm_runtime_id", runtime.identity().algorithmRuntimeId());
        root.set("effective_algorithm_definition", runtime.getAlgorithmDefinition().rawAlgorithmDefinition().deepCopy());
        root.set("parameter_metadata", runtime.getAlgorithmParameterMetadataJson());
        return root;
    }

    static void addRuntimeMetadata(ObjectNode root, AlgorithmRuntime runtime) {
        AlgorithmDefinition def = runtime.getAlgorithmDefinition();
        AlgorithmRuntimeIdentity identity = runtime.identity();
        root.put("algorithm_runtime_id", identity.algorithmRuntimeId());

        ObjectNode algorithmNode = root.putObject("algorithm");
        algorithmNode.put("name", identity.algorithmName());
        algorithmNode.put("version", identity.algorithmVersion());
        JsonFieldSupport.putStringOrNull(algorithmNode, "hyperparameter_version", identity.hyperparameterVersion());
        algorithmNode.put("algorithm_id", identity.algorithmId());
        algorithmNode.put("hyperparameter_id", identity.hyperparameterId());
        JsonFieldSupport.putStringOrNull(
                algorithmNode,
                "git_describe",
                JsonFieldSupport.textFieldOrNull(def.rawAlgorithmDefinition(), "git_describe"));
        JsonFieldSupport.putStringOrNull(algorithmNode, "hotvect_version", runtime.getHotvectVersionFromMavenOrNull());

        AlgorithmParameterMetadata params = runtime.getAlgorithmParameterMetadataOrNull();
        if (params == null) {
            root.putNull("parameters");
            return;
        }

        ObjectNode parametersNode = root.putObject("parameters");
        parametersNode.put("parameter_id", identity.parameterId());
        parametersNode.put("ran_at", params.ranAt().toString());
        if (params.lastTestTime().isPresent()) {
            parametersNode.put("last_test_time", params.lastTestTime().get().toString());
        } else {
            parametersNode.putNull("last_test_time");
        }
    }

    static void addRuntimesMetadata(ObjectNode root, Iterable<AlgorithmRuntime> runtimes) {
        RuntimeMetadataJson.addRuntimes(root, runtimes);
    }

    private static void addServingIdentity(ObjectNode response, RuntimeSelection selection) {
        AlgorithmRuntimeIdentity identity = selection.runtime().identity();
        selection.variantId().ifPresent(variantId -> response.put("variant_id", variantId));
        response.put("algorithm_id", identity.algorithmId());
        response.put("parameter_id", identity.parameterId());
        response.put("algorithm_runtime_id", identity.algorithmRuntimeId());
    }

    static byte[] readRequestBodyBytes(InputStream inputStream, long contentLength, long maxBytes) throws IOException {
        return readRequestBodyBytes(inputStream, contentLength, maxBytes, null);
    }

    static byte[] readRequestBodyBytes(InputStream inputStream, long contentLength, long maxBytes, Long maxRequestMiBOrNull) throws IOException {
        if (contentLength > maxBytes) {
            throw new RequestBodyTooLargeException(maxBytes, maxRequestMiBOrNull);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(contentLength > 0 && contentLength <= Integer.MAX_VALUE ? (int) contentLength : 8192);
        byte[] buffer = new byte[8192];
        long totalBytesRead = 0L;
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            totalBytesRead += bytesRead;
            if (totalBytesRead > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes, maxRequestMiBOrNull);
            }
            output.write(buffer, 0, bytesRead);
        }
        return output.toByteArray();
    }

    private long maxRequestBytes() {
        return opts.maxRequestMiB * 1024L * 1024L;
    }

    @Override
    public void close() {
        try {
            closeExtensionsAndMetadata(extensions, actionMetadata, null);
        } finally {
            try {
                runtimeProvider.close();
            } finally {
                app.stop();
            }
        }
    }

    private static void closeExtensionsAndMetadata(
            List<ServerExtension> extensions,
            ActionMetadataLookup actionMetadata,
            Exception failureOrNull) {
        for (ServerExtension extension : extensions.reversed()) {
            try {
                extension.close();
            } catch (RuntimeException closeFailure) {
                if (failureOrNull == null) {
                    throw closeFailure;
                }
                failureOrNull.addSuppressed(closeFailure);
            }
        }
        try {
            actionMetadata.close();
        } catch (RuntimeException closeFailure) {
            if (failureOrNull == null) {
                throw closeFailure;
            }
            failureOrNull.addSuppressed(closeFailure);
        }
    }

    public record DecodedExampleId(String algorithmRuntimeId, String exampleId) {
    }
}
