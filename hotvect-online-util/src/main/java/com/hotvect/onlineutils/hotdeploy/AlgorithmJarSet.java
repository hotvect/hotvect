package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A direct algorithm artifact set with one isolated provider loader per JAR.
 *
 * <p>This type indexes artifacts and supplies provider selection to {@link AlgorithmGraphResolver}.
 * Each returned graph holds an explicit lease on these artifact classloaders, so closing this set
 * retires it for new loads without invalidating an already constructed graph.</p>
 */
public final class AlgorithmJarSet implements AutoCloseable {
    private static final String DEFINITION_SUFFIX = "-algorithm-definition.json";

    private final Map<String, List<ProviderCandidate>> providersByAlgorithmName;
    private final Map<AlgorithmId, List<ProviderCandidate>> providersByAlgorithmId;
    private final List<LoadedJar> loadedJars;
    private final RetainedResource artifactLifetime;

    /**
     * Loads every artifact under one explicit application parent loader.
     *
     * <p>Provider paths are normalized and sorted before loading. Equal published provider
     * identities therefore select one deterministic canonical artifact. Private dependencies never
     * consult that catalog: they remain in their immediate parent artifact.</p>
     */
    public AlgorithmJarSet(List<File> algorithmJars, ClassLoader parentClassLoader) {
        Objects.requireNonNull(algorithmJars, "algorithmJars must not be null");
        Objects.requireNonNull(parentClassLoader, "parentClassLoader must not be null");
        if (algorithmJars.isEmpty()) {
            throw new IllegalArgumentException("At least one algorithm JAR is required");
        }

        ArrayList<LoadedJar> jars = new ArrayList<>();
        TreeMap<String, List<ProviderCandidate>> candidatesByAlgorithmName = new TreeMap<>();
        LinkedHashMap<AlgorithmId, List<ProviderCandidate>> candidatesByAlgorithmId = new LinkedHashMap<>();
        try {
            for (File algorithmJar : normalizeAlgorithmJars(algorithmJars)) {
                LoadedJar loadedJar = loadJar(algorithmJar, parentClassLoader);
                jars.add(loadedJar);
                for (AlgorithmDefinition definition : loadedJar.definitions().values()) {
                    AlgorithmId identity = definition.algorithmId();
                    ProviderCandidate candidate = new ProviderCandidate(identity, loadedJar);
                    candidatesByAlgorithmName.computeIfAbsent(
                                    definition.algorithmId().algorithmName(), ignored -> new ArrayList<>())
                            .add(candidate);
                    candidatesByAlgorithmId.computeIfAbsent(
                                    definition.algorithmId(), ignored -> new ArrayList<>())
                            .add(candidate);
                }
            }
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(jars, failure);
            throw failure;
        }
        LinkedHashMap<String, List<ProviderCandidate>> immutableCandidates = new LinkedHashMap<>();
        candidatesByAlgorithmName.forEach((algorithmName, candidates) -> immutableCandidates.put(
                algorithmName,
                List.copyOf(candidates)));
        this.providersByAlgorithmName = Collections.unmodifiableMap(immutableCandidates);
        LinkedHashMap<AlgorithmId, List<ProviderCandidate>> immutableCandidatesById = new LinkedHashMap<>();
        candidatesByAlgorithmId.forEach((algorithmId, candidates) -> immutableCandidatesById.put(
                algorithmId,
                List.copyOf(candidates)));
        this.providersByAlgorithmId = Collections.unmodifiableMap(immutableCandidatesById);
        this.loadedJars = List.copyOf(jars);
        this.artifactLifetime = new RetainedResource(() -> closeLoadedJars(this.loadedJars));
    }

    /** Returns the canonical packaged definition for one algorithm. */
    public AlgorithmDefinition readAlgorithmDefinition(String algorithmName) {
        return canonicalSharedProvider(algorithmName).readDefinition(algorithmName);
    }

    ClassLoader classLoader(String algorithmName) {
        return canonicalSharedProvider(algorithmName).classLoader();
    }

    /** Resolves and constructs one direct graph through the common resolver. */
    public <ALGO extends Algorithm> AlgorithmGraph<ALGO> load(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            AlgorithmDependencies hostBindings,
            ExecutionContext executionContext,
            boolean strictAlgorithmVersionCheck,
            boolean enableFeatureLogging,
            Optional<Path> localStateRoot) {
        AlgorithmDefinition effectiveAlgorithmDefinition = parseForExecution(
                Objects.requireNonNull(algorithmDefinition, "algorithmDefinition must not be null"),
                Objects.requireNonNull(executionContext, "executionContext must not be null"));
        verifyRootDefinition(effectiveAlgorithmDefinition);
        RetainedResource.Lease artifactLease = artifactLifetime.acquire();
        try {
            AlgorithmGraph<ALGO> graph = AlgorithmGraphResolver.resolve(
                    providerCatalog(
                            executionContext,
                            strictAlgorithmVersionCheck,
                            enableFeatureLogging,
                            localStateRoot,
                            parameterFile),
                    effectiveAlgorithmDefinition,
                    hostBindings,
                    AlgorithmGraphDependencies.empty(),
                    executionContext);
            graph.addOwnedResource(artifactLease);
            return graph;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(artifactLease, failure);
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            artifactLifetime.close();
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Failed to close algorithm artifact set", error);
        }
    }

    private static void closeLoadedJars(List<LoadedJar> loadedJars) throws IOException {
        IOException failure = null;
        for (int index = loadedJars.size() - 1; index >= 0; index--) {
            try {
                loadedJars.get(index).classLoader().close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeAfterFailure(RetainedResource.Lease lease, Throwable failure) {
        try {
            lease.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static AlgorithmDefinition parseForExecution(
            AlgorithmDefinition algorithmDefinition,
            ExecutionContext executionContext) {
        try {
            return new AlgorithmDefinitionReader(executionContext.inputSemantic())
                    .parse(algorithmDefinition.rawAlgorithmDefinition());
        } catch (IOException error) {
            throw new MalformedAlgorithmException(error);
        }
    }

    private AlgorithmGraphResolver.ProviderCatalog providerCatalog(
            ExecutionContext executionContext,
            boolean strictAlgorithmVersionCheck,
            boolean enableFeatureLogging,
            Optional<Path> localStateRoot,
            File parameterFile) {
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        Objects.requireNonNull(localStateRoot, "localStateRoot must not be null");
        IdentityHashMap<LoadedJar, JarProvider> providers = new IdentityHashMap<>();
        for (LoadedJar loadedJar : loadedJars) {
            providers.put(loadedJar, new JarProvider(
                    loadedJar,
                new AlgorithmInstanceFactory(
                            loadedJar.classLoader(),
                            new AlgorithmInstanceFactory.Options(
                                    executionContext.inputSemantic(),
                                    strictAlgorithmVersionCheck,
                                    enableFeatureLogging,
                                    localStateRoot)),
                    parameterFile));
        }
        return new AlgorithmGraphResolver.ProviderCatalog() {
            @Override
            public AlgorithmGraphResolver.Provider rootProvider(AlgorithmDefinition rootDefinition) {
                return providerFor(AlgorithmJarSet.this.rootProvider(rootDefinition));
            }

            @Override
            public AlgorithmGraphResolver.Provider privateProvider(
                    AlgorithmGraphResolver.Provider parent,
                    AlgorithmDependencyDeclaration.Private declaration) {
                if (!(parent instanceof JarProvider jarProvider)) {
                    throw new IllegalStateException(
                            "Direct private dependency " + declaration.name()
                                    + " has no artifact provider");
                }
                return jarProvider;
            }

            @Override
            public AlgorithmGraphResolver.Provider sharedProvider(
                    AlgorithmDependencyDeclaration.Shared declaration) {
                return providerFor(canonicalSharedProvider(declaration.algorithmId()));
            }

            private JarProvider providerFor(LoadedJar loadedJar) {
                JarProvider provider = providers.get(loadedJar);
                if (provider == null) {
                    throw new IllegalStateException("No node factory for " + loadedJar.file());
                }
                return provider;
            }
        };
    }

    private void verifyRootDefinition(AlgorithmDefinition definition) {
        LoadedJar provider = rootProvider(definition);
        AlgorithmDefinition packaged = provider.readDefinition(definition.algorithmId().algorithmName());
        if (!packaged.algorithmId().equals(definition.algorithmId())) {
            throw new IllegalArgumentException(
                    "Algorithm definition " + definition.algorithmId()
                            + " does not match the packaged definition "
                            + packaged.algorithmId() + " in " + provider.file());
        }
    }

    private LoadedJar rootProvider(AlgorithmDefinition definition) {
        return canonicalSharedProvider(definition.algorithmId());
    }

    private LoadedJar canonicalSharedProvider(String algorithmName) {
        LinkedHashMap<AlgorithmId, LoadedJar> distinctProviders = new LinkedHashMap<>();
        for (ProviderCandidate candidate : providersFor(algorithmName)) {
            distinctProviders.putIfAbsent(candidate.identity(), candidate.loadedJar());
        }
        if (distinctProviders.size() != 1) {
            throw new IllegalArgumentException(
                    "Algorithm " + algorithmName + " has conflicting provider identities: "
                            + describeProviders(providersFor(algorithmName)));
        }
        return distinctProviders.values().iterator().next();
    }

    private LoadedJar canonicalSharedProvider(AlgorithmId algorithmId) {
        List<ProviderCandidate> candidates = providersByAlgorithmId.get(algorithmId);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("No algorithm JAR defines " + algorithmId);
        }
        return candidates.getFirst().loadedJar();
    }

    private List<ProviderCandidate> providersFor(String algorithmName) {
        List<ProviderCandidate> providers = providersByAlgorithmName.get(algorithmName);
        if (providers == null) {
            throw new IllegalArgumentException(
                    "No algorithm JAR defines " + algorithmName
                            + "; available algorithms: " + providersByAlgorithmName.keySet());
        }
        return providers;
    }

    private static String describeProviders(List<ProviderCandidate> providers) {
        return String.join(", ", providers.stream()
                .map(candidate -> candidate.identity() + " in " + candidate.loadedJar().file())
                .toList());
    }

    private static List<File> normalizeAlgorithmJars(List<File> algorithmJars) {
        TreeMap<String, File> normalized = new TreeMap<>();
        for (File algorithmJar : algorithmJars) {
            if (algorithmJar == null || !algorithmJar.isFile()) {
                throw new IllegalArgumentException("Algorithm JAR does not exist or is not a file: " + algorithmJar);
            }
            try {
                File canonicalJar = algorithmJar.getCanonicalFile();
                normalized.putIfAbsent(canonicalJar.getPath(), canonicalJar);
            } catch (IOException error) {
                throw new IllegalArgumentException("Cannot canonicalize algorithm JAR " + algorithmJar, error);
            }
        }
        return List.copyOf(normalized.values());
    }

    private static LoadedJar loadJar(File algorithmJar, ClassLoader parentClassLoader) {
        if (algorithmJar == null || !algorithmJar.isFile()) {
            throw new IllegalArgumentException("Algorithm JAR does not exist or is not a file: " + algorithmJar);
        }
        try {
            Map<String, AlgorithmDefinition> definitions = readDefinitions(algorithmJar);
            if (definitions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Algorithm JAR contains no root-level *-algorithm-definition.json resources: "
                                + algorithmJar);
            }
            return new LoadedJar(
                    algorithmJar.getAbsoluteFile(),
                    HotvectFactory.newAlgorithmClassLoader(algorithmJar, parentClassLoader),
                    definitions);
        } catch (MalformedAlgorithmException error) {
            throw error;
        } catch (Exception error) {
            throw new MalformedAlgorithmException("Could not load algorithm JAR " + algorithmJar, error);
        }
    }

    private static Map<String, AlgorithmDefinition> readDefinitions(File algorithmJar) throws IOException {
        TreeMap<String, AlgorithmDefinition> definitions = new TreeMap<>();
        // Index the committed contract before resolving its shared dependencies for offline use.
        AlgorithmDefinitionReader reader = new AlgorithmDefinitionReader(
                AlgorithmDefinitionReader.DependencyResolution.OFFLINE);
        try (ZipFile zipFile = new ZipFile(algorithmJar)) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String resourceName = entry.getName();
                if (entry.isDirectory() || resourceName.contains("/") || !resourceName.endsWith(DEFINITION_SUFFIX)) {
                    continue;
                }
                AlgorithmDefinition definition;
                try (InputStream input = zipFile.getInputStream(entry)) {
                    definition = reader.parseCommitted(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                String expectedResource = definition.algorithmId().algorithmName() + DEFINITION_SUFFIX;
                if (!resourceName.equals(expectedResource)) {
                    throw new IllegalArgumentException(
                            "Algorithm definition resource " + resourceName + " declares "
                                    + definition.algorithmId().algorithmName() + " in " + algorithmJar);
                }
                if (definitions.putIfAbsent(definition.algorithmId().algorithmName(), definition) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate algorithm definition for " + definition.algorithmId().algorithmName()
                                    + " in " + algorithmJar);
                }
            }
        }
        return Map.copyOf(definitions);
    }

    private static void closeAfterFailure(List<LoadedJar> jars, Throwable failure) {
        for (int index = jars.size() - 1; index >= 0; index--) {
            try {
                jars.get(index).classLoader().close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private record LoadedJar(
            File file,
            StrictChildFirstClassLoader classLoader,
            Map<String, AlgorithmDefinition> definitions) {
        private LoadedJar {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(classLoader, "classLoader must not be null");
            definitions = Map.copyOf(Objects.requireNonNull(definitions, "definitions must not be null"));
        }

        private AlgorithmDefinition readDefinition(String algorithmName) {
            AlgorithmDefinition definition = definitions.get(algorithmName);
            if (definition == null) {
                throw new IllegalArgumentException(
                        "Algorithm JAR " + file + " does not define " + algorithmName
                                + "; available algorithms: " + definitions.keySet());
            }
            return definition;
        }
    }

    private record ProviderCandidate(AlgorithmId identity, LoadedJar loadedJar) {
        private ProviderCandidate {
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(loadedJar, "loadedJar must not be null");
        }
    }

    private record JarProvider(LoadedJar loadedJar, AlgorithmInstanceFactory factory, File parameterFile)
            implements AlgorithmGraphResolver.Provider {
        private JarProvider {
            Objects.requireNonNull(loadedJar, "loadedJar must not be null");
            Objects.requireNonNull(factory, "factory must not be null");
        }

        @Override
        public AlgorithmDefinition readDefinition(String algorithmName) {
            return factory.readAlgorithmDefinition(algorithmName);
        }

        @Override
        public ClassLoader classLoader() {
            return loadedJar.classLoader();
        }
    }
}
