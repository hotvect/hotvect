package com.hotvect.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.ZipFiles;
import com.hotvect.utils.AlgorithmDefinitionOverrideUtils;
import com.hotvect.utils.AlgorithmDefinitionReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.ZipFile;

final class AlgorithmRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AlgorithmRuntime.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final File algorithmJar;
    private final File parameterPath;
    private final AlgorithmDefinition algorithmDefinition;
    private final AlgorithmInstance<?> algorithmInstance;
    private final AlgorithmRuntimeId identity;
    private final ClassLoader rootArtifactClassLoader;
    private final AlgorithmGraph<?> algorithmGraph;
    private final AlgorithmInstanceFactory artifactFactory;
    private final String hotvectVersion;

    AlgorithmRuntime(File algorithmJar, String algorithmName, File algorithmOverride, File parameterPath) throws Exception {
        this.algorithmJar = algorithmJar;
        this.parameterPath = parameterPath;
        AlgorithmInstanceFactory factory = new AlgorithmInstanceFactory(
                algorithmJar,
                Thread.currentThread().getContextClassLoader(),
                new AlgorithmInstanceFactory.Options(
                        InputSemantic.OFFLINE,
                        false,
                        false,
                        Optional.empty()));
        AlgorithmGraph<?> graph = null;
        try {
            AlgorithmDefinition baseDefinition = factory.readAlgorithmDefinition(algorithmName);
            this.algorithmDefinition = applyAlgorithmOverride(baseDefinition, algorithmOverride);
            graph = factory.loadGraph(
                    this.algorithmDefinition,
                    parameterPath,
                    AlgorithmDependencies.empty(),
                    ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE));
            this.algorithmGraph = graph;
            this.algorithmInstance = graph.root();
            this.identity = graph.runtimeId();
            this.rootArtifactClassLoader = graph.rootArtifactClassLoader();
            this.artifactFactory = factory;
            this.hotvectVersion = readHotvectVersionFromMaven(algorithmJar);
        } catch (Throwable failure) {
            closeAfterConstructionFailure(graph, factory, failure);
            throw failure;
        }
    }

    AlgorithmRuntime(
            AlgorithmInstance<?> algorithmInstance,
            ClassLoader rootArtifactClassLoader) throws Exception {
        this.algorithmJar = null;
        this.parameterPath = null;
        this.algorithmInstance = Objects.requireNonNull(algorithmInstance, "algorithmInstance must not be null");
        this.algorithmDefinition = algorithmInstance.algorithmDefinition();
        this.identity = AlgorithmRuntimeId.from(this.algorithmInstance, Map.of());
        this.rootArtifactClassLoader = Objects.requireNonNull(
                rootArtifactClassLoader,
                "rootArtifactClassLoader must not be null");
        this.algorithmGraph = null;
        this.artifactFactory = null;
        this.hotvectVersion = null;
    }

    AlgorithmDefinition getAlgorithmDefinition() {
        return algorithmDefinition;
    }

    AlgorithmParameterMetadata getAlgorithmParameterMetadataOrNull() {
        return algorithmInstance.algorithmParameterMetadata();
    }

    JsonNode getAlgorithmParameterMetadataJson() throws IOException {
        if (parameterPath != null) {
            return readAlgorithmParameterMetadataJson(algorithmDefinition, parameterPath);
        }
        AlgorithmParameterMetadata metadata = algorithmInstance.algorithmParameterMetadata();
        return metadata == null ? OM.getNodeFactory().nullNode() : parameterMetadataJson(metadata);
    }

    AlgorithmRuntimeId identity() {
        return identity;
    }

    AlgorithmInstance<?> algorithmInstance() {
        return algorithmInstance;
    }

    ClassLoader rootArtifactClassLoader() {
        return rootArtifactClassLoader;
    }

    String algorithmJarPathOrNull() {
        return algorithmJar == null ? null : algorithmJar.getAbsolutePath();
    }

    String parameterPathOrNull() {
        return parameterPath == null ? null : parameterPath.getAbsolutePath();
    }

    /**
     * Hotvect module versions found in the algorithm JAR, or null when this runtime was built from an
     * already-constructed instance or the JAR declares no Hotvect modules. Resolved once at construction:
     * {@code GET /api/metadata} is polled by the demo UI and must not reopen the JAR per request.
     */
    String getHotvectVersionFromMavenOrNull() {
        return hotvectVersion;
    }

    private static String readHotvectVersionFromMaven(File algorithmJar) {
        SortedSet<String> versions = new TreeSet<>();
        try (ZipFile zf = new ZipFile(algorithmJar)) {
            addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-core", versions);
            addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-tensorflow", versions);
            addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-catboost", versions);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read Hotvect module versions from algorithm JAR " + algorithmJar, e);
        }
        if (versions.isEmpty()) {
            return null;
        }
        return String.join(", ", versions);
    }

    private static JsonNode readAlgorithmParameterMetadataJson(
            AlgorithmDefinition algorithmDefinition,
            File parameterPath) throws IOException {
        String entryName = algorithmDefinition.algorithmId().algorithmName() + "/algorithm-parameters.json";
        try (ZipFile zipFile = new ZipFile(parameterPath);
             InputStream input = ZipFiles.readFromZipByName(zipFile, entryName)) {
            return OM.readTree(input);
        }
    }

    private static ObjectNode parameterMetadataJson(AlgorithmParameterMetadata metadata) {
        ObjectNode node = OM.createObjectNode();
        node.put("algorithm_name", metadata.algorithmId().algorithmName());
        node.put("algorithm_version", metadata.algorithmId().algorithmVersion());
        node.put("parameter_id", metadata.parameterId());
        node.put("ran_at", metadata.ranAt().toString());
        metadata.lastTestTime().ifPresentOrElse(
                lastTestTime -> node.put("last_test_time", lastTestTime.toString()),
                () -> node.putNull("last_test_time"));
        return node;
    }

    private static void addMavenPomPropertiesVersionIfPresent(ZipFile zf, String groupId, String artifactId, Set<String> versions)
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

    static AlgorithmDefinition applyAlgorithmOverride(AlgorithmDefinition baseDefinition, File overrideFile) throws IOException {
        if (overrideFile == null) {
            return baseDefinition;
        }
        JsonNode overrideNode = OM.readTree(overrideFile);
        if (!overrideNode.isObject()) {
            throw new ContractViolationException("--algorithm-override must be a JSON object", overrideFile.getAbsolutePath());
        }
        AlgorithmDefinitionReader.DependencyResolution dependencyResolution =
                AlgorithmDefinitionReader.DependencyResolution.OFFLINE;
        JsonNode merged = AlgorithmDefinitionOverrideUtils.applyOverride(
                baseDefinition.rawAlgorithmDefinition(),
                overrideNode,
                dependencyResolution);
        try {
            return new AlgorithmDefinitionReader(dependencyResolution).parse(merged);
        } catch (IOException e) {
            throw new IOException("Failed to parse merged algorithm definition after applying --algorithm-override", e);
        }
    }

    @Override
    public void close() {
        Throwable failure = null;
        try {
            if (algorithmGraph != null) {
                algorithmGraph.close();
            }
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        }
        try {
            if (artifactFactory != null) {
                artifactFactory.close();
            }
        } catch (Throwable closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            log.warn("Failed to close algorithm runtime", failure);
        }
    }

    private static void closeAfterConstructionFailure(
            AlgorithmGraph<?> graph,
            AlgorithmInstanceFactory factory,
            Throwable failure) {
        if (graph != null) {
            try {
                graph.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        try {
            factory.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

}
