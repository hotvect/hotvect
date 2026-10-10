package com.hotvect.serve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ServeApplication implements AutoCloseable {
    static final long MAX_BUFFERED_REQUEST_MIB = 512;
    private static final Logger log = LoggerFactory.getLogger(ServeApplication.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final ServerOptions opts;
    private final List<ServerExtension> extensions;
    private final boolean staticResourcesEnabled;
    private final LocalRuntimeCatalog runtimeCatalog;
    private ConfigurableApplicationContext applicationContext;

    public static int runUntilInterrupted(
            ServerOptions opts,
            List<ServerExtension> extensions,
            boolean staticResourcesEnabled) throws Exception {
        ServeApplication app = null;
        try {
            app = new ServeApplication(opts, extensions, staticResourcesEnabled);
            try (ServeApplication runningApp = app) {
                runningApp.start();
                Thread.sleep(Long.MAX_VALUE);
                return 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            if (app == null) {
                closeExtensions(extensions, e);
            }
            throw e;
        }
    }

    public ServeApplication(
            ServerOptions opts,
            List<ServerExtension> extensions,
            boolean staticResourcesEnabled) throws Exception {
        this(opts, extensions, staticResourcesEnabled, null);
    }

    ServeApplication(
            ServerOptions opts,
            List<ServerExtension> extensions,
            boolean staticResourcesEnabled,
            LocalRuntimeCatalog runtimeCatalog) throws Exception {
        this.opts = Objects.requireNonNull(opts);
        this.extensions = List.copyOf(Objects.requireNonNull(extensions));
        this.staticResourcesEnabled = staticResourcesEnabled;

        ValidationSupport.requireArgument(
                opts.port >= 1 && opts.port <= 65535,
                "--port must be between 1 and 65535, got %s",
                opts.port);
        ValidationSupport.requireArgument(
                opts.maxRequestMiB >= 1 && opts.maxRequestMiB <= MAX_BUFFERED_REQUEST_MIB,
                "--max-request-mib must be between 1 and %s, got %s",
                MAX_BUFFERED_REQUEST_MIB,
                opts.maxRequestMiB);

        this.runtimeCatalog = runtimeCatalog == null
                ? LocalRuntimeCatalog.create(opts)
                : runtimeCatalog;
        try {
            for (ServerExtension extension : this.extensions) {
                extension.initialize(this);
            }
        } catch (RuntimeException failure) {
            this.runtimeCatalog.close();
            throw failure;
        }
    }

    public void start() {
        SpringApplication spring = new SpringApplication(ServeWebApplication.class);
        spring.setDefaultProperties(Map.of(
                "server.address", opts.host,
                "server.port", Integer.toString(opts.port),
                "server.shutdown", "graceful",
                "spring.application.name", "hotvect-algorithm-demo",
                "spring.main.banner-mode", "off",
                "spring.web.resources.add-mappings", Boolean.toString(staticResourcesEnabled),
                "spring.threads.virtual.enabled", "true"));
        spring.addInitializers(this::registerRuntimeBeans);
        applicationContext = spring.run();

        int boundPort = ((WebServerApplicationContext) applicationContext).getWebServer().getPort();
        String baseUrl = "http://" + (Objects.equals(opts.host, "0.0.0.0") ? "127.0.0.1" : opts.host) + ":" + boundPort;
        log.info("Hotvect algorithm demo server started at {}", baseUrl);
        for (ServerExtension extension : extensions) {
            extension.onStarted(baseUrl);
        }
    }

    private void registerRuntimeBeans(ConfigurableApplicationContext configurableContext) {
        applicationContext = configurableContext;
        GenericApplicationContext context = (GenericApplicationContext) configurableContext;
        context.registerBean(
                "hotvectServeRuntime",
                ServeApplication.class,
                () -> this,
                definition -> definition.setDestroyMethodName(""));
        context.registerBean(
                "hotvectLocalRuntimeCatalog",
                LocalRuntimeCatalog.class,
                () -> runtimeCatalog);
        for (int index = 0; index < extensions.size(); index++) {
            registerExtensionBean(context, extensions.get(index), index);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerExtensionBean(
            GenericApplicationContext context,
            ServerExtension extension,
            int index) {
        context.registerBean(
                "hotvectServerExtension" + index,
                (Class) extension.getClass(),
                () -> extension);
    }

    public byte[] readRequestBodyBytes(HttpServletRequest request) throws IOException {
        return readRequestBodyBytes(
                request.getInputStream(),
                request.getContentLengthLong(),
                maxRequestBytes(),
                opts.maxRequestMiB
        );
    }

    public SelectedRuntime selectRuntime(String algorithmRuntimeIdOrNull) {
        AlgorithmRuntime runtime = runtimeCatalog
                .selectOrDefault(JsonFieldSupport.blankToNull(algorithmRuntimeIdOrNull));
        return new SelectedRuntime(
                runtime.algorithmInstance(),
                runtime.identity(),
                runtime.rootArtifactClassLoader());
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
        AlgorithmRuntime runtime = runtimeCatalog.selectOrDefault(null);

        RuntimeMetadataJson.addRuntime(root, runtime);
        RuntimeMetadataJson.addRuntimes(root, runtimeCatalog.runtimes());

        for (ServerExtension extension : extensions) {
            extension.addMetadata(root);
        }
        return root;
    }

    public ObjectNode buildRuntimeDetails(String algorithmRuntimeIdOrNull) throws IOException {
        AlgorithmRuntime runtime = runtimeCatalog
                .selectOrDefault(JsonFieldSupport.blankToNull(algorithmRuntimeIdOrNull));
        ObjectNode root = OM.createObjectNode();
        root.put("algorithm_runtime_id", runtime.identity().value());
        root.set("effective_algorithm_definition", runtime.getAlgorithmDefinition().rawAlgorithmDefinition());
        root.set("parameter_metadata", runtime.getAlgorithmParameterMetadataJson());
        return root;
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
        ConfigurableApplicationContext context = applicationContext;
        applicationContext = null;
        if (context != null) {
            context.close();
        } else {
            try {
                closeExtensions(extensions, null);
            } finally {
                runtimeCatalog.close();
            }
        }
    }

    private static void closeExtensions(
            List<ServerExtension> extensions,
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
    }
}
