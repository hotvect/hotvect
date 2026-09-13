package com.hotvect.offlineutils.commandline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

public class DirectDomainModelJarSupportTest {
    private static final String ALGORITHM_NAME = "direct-domain-model";
    private static final String SHARED_TYPE_NAME = "fixtures.shared.SharedType";
    private static final String SHARED_TYPE_RESOURCE = "fixtures/shared/SharedType.class";
    private static final String DEPENDENCY_NAME = "fixtures.additional.Dependency";
    private static final String INTROSPECTION_NAME = "fixtures.algorithm.AlgorithmIntrospection";
    private static final String RANKER_FACTORY_RESOURCE = "fixtures/algorithm/RecordingRankerFactory.class";
    private static final String RECORDING_FAILURE_FLAG = "hotvect.test.recordingRanker.fail";

    @TempDir
    Path tempDir;

    @AfterEach
    void clearFailureFlag() {
        System.clearProperty(RECORDING_FAILURE_FLAG);
        ObservedLoaders.reset();
    }

    @Test
    void directPredictAcceptsDomainModelJar() {
        Main.PredictCommand predict = new Main.PredictCommand();
        CommandLine commandLine = new CommandLine(predict);
        commandLine.parseArgs(
                "--algorithm-jar", "algorithm.jar",
                "--algorithm-definition", ALGORITHM_NAME,
                "--domain-model-jar", "domain-model.jar",
                "--additional-jars", "dependency.jar",
                "--source", "requests.jsonl",
                "--dest", "predictions");

        OfflineAlgorithmSource.Direct source = assertInstanceOf(
                OfflineAlgorithmSource.Direct.class,
                Main.selectedComposedAlgorithmSource(
                        commandLine.getCommandSpec(),
                        predict.algorithmSource,
                        predict.domainModelJars.jars));

        assertEquals(List.of(new File("domain-model.jar")), source.domainModelJars());
        assertEquals(List.of(new File("dependency.jar")), source.additionalJars());
    }

    @Test
    void directPerformanceTestAcceptsDomainModelJar() {
        Main.PerformanceTestCommand performanceTest = new Main.PerformanceTestCommand();
        CommandLine commandLine = new CommandLine(performanceTest);
        commandLine.parseArgs(
                "--algorithm-jar", "algorithm.jar",
                "--algorithm-definition", ALGORITHM_NAME,
                "--domain-model-jar", "domain-model.jar",
                "--source", "requests.jsonl");

        OfflineAlgorithmSource.Direct source = assertInstanceOf(
                OfflineAlgorithmSource.Direct.class,
                Main.selectedComposedAlgorithmSource(
                        commandLine.getCommandSpec(),
                        performanceTest.algorithmSource,
                        performanceTest.domainModelJars.jars));

        assertEquals(List.of(new File("domain-model.jar")), source.domainModelJars());
    }

    @Test
    void directTaskContextLoadsDomainModelClassesFromParentLoader() throws Exception {
        DirectFixture fixture = createDirectFixture();
        OfflineAlgorithmSource.Direct source = new OfflineAlgorithmSource.Direct(
                fixture.algorithmJar().toFile(),
                ALGORITHM_NAME,
                List.of(fixture.domainModelJar().toFile()),
                List.of(fixture.additionalJar().toFile()),
                null);
        URLClassLoader algorithmLoader;
        URLClassLoader domainLoader;

        try (OfflineTaskContext context = getDirectTaskContext("predict", source)) {
            algorithmLoader = context.classLoader();
            domainLoader = assertInstanceOf(URLClassLoader.class, algorithmLoader.getParent());

            Class<?> sharedType = algorithmLoader.loadClass(SHARED_TYPE_NAME);
            Class<?> dependency = algorithmLoader.loadClass(DEPENDENCY_NAME);
            Class<?> introspection = algorithmLoader.loadClass(INTROSPECTION_NAME);

            assertSame(sharedType, domainLoader.loadClass(SHARED_TYPE_NAME));
            assertSame(domainLoader, sharedType.getClassLoader());
            assertSame(algorithmLoader, dependency.getClassLoader());
            assertSame(algorithmLoader, introspection.getClassLoader());

            Object algorithmShared = introspection.getMethod("fromAlgorithm").invoke(null);
            Object additionalShared = dependency.getMethod("fromAdditional").invoke(null);

            assertSame(sharedType, algorithmShared.getClass());
            assertSame(sharedType, additionalShared.getClass());
            assertEquals("algorithm:domain-parent", introspection.getMethod("roundTrip").invoke(null));
            assertEquals("domain-parent", introspection.getMethod("duplicateOrigin").invoke(null));
        }

        assertLoaderClosed(algorithmLoader, RANKER_FACTORY_RESOURCE);
        assertLoaderClosed(domainLoader, SHARED_TYPE_RESOURCE);
    }

    @Test
    void additionalJarsRemainInTheDirectAlgorithmLoaderWithoutDomainModelJar() throws Exception {
        DirectFixture fixture = createDirectFixture();
        OfflineAlgorithmSource.Direct source = new OfflineAlgorithmSource.Direct(
                fixture.algorithmJar().toFile(),
                ALGORITHM_NAME,
                List.of(),
                List.of(fixture.additionalJar().toFile()),
                null);

        try (OfflineTaskContext context = getDirectTaskContext("predict", source)) {
            URLClassLoader algorithmLoader = context.classLoader();
            assertSame(Main.class.getClassLoader(), algorithmLoader.getParent());

            Class<?> sharedType = algorithmLoader.loadClass(SHARED_TYPE_NAME);
            Class<?> dependency = algorithmLoader.loadClass(DEPENDENCY_NAME);
            Class<?> introspection = algorithmLoader.loadClass(INTROSPECTION_NAME);

            assertSame(algorithmLoader, sharedType.getClassLoader());
            assertSame(algorithmLoader, dependency.getClassLoader());
            assertSame(algorithmLoader, introspection.getClassLoader());
            assertEquals("algorithm:algorithm-shadow", introspection.getMethod("roundTrip").invoke(null));
        }
    }

    @Test
    void missingDomainModelJarFailsWithAClearMessage() throws Exception {
        DirectFixture fixture = createDirectFixture();
        File missingJar = tempDir.resolve("missing-domain-model.jar").toFile();
        OfflineAlgorithmSource.Direct source = new OfflineAlgorithmSource.Direct(
                fixture.algorithmJar().toFile(),
                ALGORITHM_NAME,
                List.of(missingJar),
                List.of(fixture.additionalJar().toFile()),
                null);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> getDirectTaskContext("predict", source));

        assertTrue(failure.getMessage().contains("Domain-model JAR does not exist or is not a file"));
        assertTrue(failure.getMessage().contains(missingJar.getPath()));
    }

    @Test
    void runTaskClosesDirectLoadersAfterSuccess() throws Exception {
        DirectFixture fixture = createDirectFixture();
        Options options = predictOptions(fixture, false);

        assertEquals(0, Main.runTask("predict", options));

        assertLoadersRecordedAndClosed();
    }

    @Test
    void runTaskClosesDirectLoadersAfterLaterSetupFailure() throws Exception {
        DirectFixture fixture = createDirectFixture();
        Options options = predictOptions(fixture, true);

        assertEquals(1, Main.runTask("predict", options));

        assertLoadersRecordedAndClosed();
    }

    @Test
    void fixedAndEmsSelectionsStillRetainDomainModelJars() {
        CommandLine fixedLine = new CommandLine(new Main.PredictCommand());
        Main.PredictCommand fixed = (Main.PredictCommand) fixedLine.getCommand();
        fixedLine.parseArgs(
                "--composition", "composition.json",
                "--domain-model-jar", "domain.jar",
                "--source", "requests.jsonl",
                "--dest", "predictions");

        OfflineAlgorithmSource.Fixed fixedSource = assertInstanceOf(
                OfflineAlgorithmSource.Fixed.class,
                Main.selectedComposedAlgorithmSource(
                        fixedLine.getCommandSpec(),
                        fixed.algorithmSource,
                        fixed.domainModelJars.jars));
        assertEquals(List.of(new File("domain.jar")), fixedSource.domainModelJars());

        CommandLine emsLine = new CommandLine(new Main.PredictCommand());
        Main.PredictCommand ems = (Main.PredictCommand) emsLine.getCommand();
        emsLine.parseArgs(
                "--ems-slot", "slot",
                "--ems-state", "state.json",
                "--assignment-key-json-pointer", "/customer_id",
                "--domain-model-jar", "domain.jar",
                "--source", "requests.jsonl",
                "--dest", "predictions");

        OfflineAlgorithmSource.Ems emsSource = assertInstanceOf(
                OfflineAlgorithmSource.Ems.class,
                Main.selectedComposedAlgorithmSource(
                        emsLine.getCommandSpec(),
                        ems.algorithmSource,
                        ems.domainModelJars.jars));
        assertEquals(List.of(new File("domain.jar")), emsSource.domainModelJars());
    }

    private Options predictOptions(DirectFixture fixture, boolean failDuringSetup) throws IOException {
        Path input = tempDir.resolve(failDuringSetup ? "failing-input.jsonl" : "input.jsonl");
        Files.writeString(input, "{\"request\":\"value\"}\n");
        Path output = tempDir.resolve(failDuringSetup ? "failing-output" : "output");
        Path metadata = tempDir.resolve(failDuringSetup ? "failing-metadata" : "metadata");

        if (failDuringSetup) {
            System.setProperty(RECORDING_FAILURE_FLAG, "true");
        }

        Options options = new Options();
        options.algorithmSource = new OfflineAlgorithmSource.Direct(
                fixture.algorithmJar().toFile(),
                ALGORITHM_NAME,
                List.of(fixture.domainModelJar().toFile()),
                List.of(fixture.additionalJar().toFile()),
                null);
        options.sourceFiles = Map.of("default", List.of(input.toFile()));
        options.destinationFile = output.toFile();
        options.metadataLocation = metadata.toFile();
        options.maxThreads = 1;
        options.batchSize = 1;
        options.queueLength = 1;
        options.readQueueLength = 1;
        options.writeQueueLength = 1;
        options.ordered = true;
        return options;
    }

    private void assertLoadersRecordedAndClosed() {
        URLClassLoader algorithmLoader = assertInstanceOf(URLClassLoader.class, ObservedLoaders.algorithmLoader());
        URLClassLoader domainLoader = assertInstanceOf(URLClassLoader.class, ObservedLoaders.parentLoader());

        assertLoaderClosed(algorithmLoader, RANKER_FACTORY_RESOURCE);
        assertLoaderClosed(domainLoader, SHARED_TYPE_RESOURCE);
    }

    private static void assertLoaderClosed(URLClassLoader loader, String resourceName) {
        assertNull(loader.getResource(resourceName));
    }

    private OfflineTaskContext getDirectTaskContext(String taskName, OfflineAlgorithmSource.Direct source) throws Exception {
        Options options = new Options();
        options.algorithmSource = source;
        return Main.getDirectTaskContext(taskName, options, source);
    }

    private DirectFixture createDirectFixture() throws IOException {
        Path domainModelJar = compileJar(
                "domain-model.jar",
                Map.of(
                        SHARED_TYPE_NAME,
                        """
                                package fixtures.shared;

                                public final class SharedType {
                                    private final String value;

                                    public SharedType(String value) {
                                        this.value = value;
                                    }

                                    public String value() {
                                        return value;
                                    }

                                    public String origin() {
                                        return "domain-parent";
                                    }
                                }
                                """),
                Map.of(),
                List.of());
        Path additionalJar = compileJar(
                "additional.jar",
                Map.of(
                        DEPENDENCY_NAME,
                        """
                                package fixtures.additional;

                                import fixtures.shared.SharedType;

                                public final class Dependency {
                                    private Dependency() {
                                    }

                                    public static SharedType fromAdditional() {
                                        return new SharedType("additional");
                                    }

                                    public static String accept(SharedType value) {
                                        return value.value() + ":" + value.origin();
                                    }
                                }
                                """),
                Map.of(),
                List.of(domainModelJar));
        Path algorithmJar = compileJar(
                "algorithm.jar",
                algorithmSources(),
                Map.of(
                        ALGORITHM_NAME + "-algorithm-definition.json",
                        """
                                {
                                  "algorithm_name": "%s",
                                  "algorithm_version": "1",
                                  "decoder_factory_classname": "fixtures.algorithm.DecoderFactory",
                                  "reward_function_factory_classname": "fixtures.algorithm.RewardFactory",
                                  "algorithm_factory_classname": "fixtures.algorithm.RecordingRankerFactory"
                                }
                                """.formatted(ALGORITHM_NAME)),
                List.of(additionalJar));
        return new DirectFixture(domainModelJar, additionalJar, algorithmJar);
    }

    private Map<String, String> algorithmSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                SHARED_TYPE_NAME,
                """
                        package fixtures.shared;

                        public final class SharedType {
                            private final String value;

                            public SharedType(String value) {
                                this.value = value;
                            }

                            public String value() {
                                return value;
                            }

                            public String origin() {
                                return "algorithm-shadow";
                            }
                        }
                        """);
        sources.put(
                INTROSPECTION_NAME,
                """
                        package fixtures.algorithm;

                        import fixtures.additional.Dependency;
                        import fixtures.shared.SharedType;

                        public final class AlgorithmIntrospection {
                            private AlgorithmIntrospection() {
                            }

                            public static SharedType fromAlgorithm() {
                                return new SharedType("algorithm");
                            }

                            public static String roundTrip() {
                                return Dependency.accept(new SharedType("algorithm"));
                            }

                            public static String duplicateOrigin() {
                                return new SharedType("duplicate").origin();
                            }
                        }
                        """);
        sources.put(
                "fixtures.algorithm.DecoderFactory",
                """
                        package fixtures.algorithm;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.google.common.collect.ImmutableList;
                        import com.hotvect.api.data.AvailableAction;
                        import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
                        import com.hotvect.api.codec.ranking.RankingExampleDecoder;
                        import com.hotvect.api.data.ranking.RankingExample;
                        import com.hotvect.api.data.ranking.RankingDecision;
                        import com.hotvect.api.data.ranking.RankingOutcome;
                        import com.hotvect.api.data.ranking.RankingRequest;
                        import java.util.Optional;

                        public final class DecoderFactory implements RankingExampleDecoderFactory<String, String, String> {
                            @Override
                            public RankingExampleDecoder<String, String, String> apply(Optional<JsonNode> hyperparameter) {
                                return line -> ImmutableList.of(new RankingExample<>(
                                        "fixture",
                                        RankingRequest.ofAvailableActions(
                                                "request",
                                                "shared",
                                                ImmutableList.of(
                                                        AvailableAction.of("action-1", "action-1"),
                                                        AvailableAction.of("action-2", "action-2"))),
                                        ImmutableList.of(
                                                new RankingOutcome<>(
                                                        RankingDecision.builder("action-1", 0, "action-1").build(),
                                                        "outcome-1"),
                                                new RankingOutcome<>(
                                                        RankingDecision.builder("action-2", 1, "action-2").build(),
                                                        "outcome-2"))));
                            }
                        }
                        """);
        sources.put(
                "fixtures.algorithm.RewardFactory",
                """
                        package fixtures.algorithm;

                        import com.hotvect.api.algodefinition.common.RewardFunction;
                        import com.hotvect.api.algodefinition.common.RewardFunctionFactory;

                        public final class RewardFactory implements RewardFunctionFactory<String> {
                            @Override
                            public RewardFunction<String> get() {
                                return outcome -> 1.0;
                            }
                        }
                        """);
        sources.put(
                "fixtures.algorithm.RecordingRankerFactory",
                """
                        package fixtures.algorithm;

                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
                        import com.hotvect.api.algorithms.Ranker;
                        import com.hotvect.api.data.ranking.RankingDecision;
                        import com.hotvect.api.data.ranking.RankingResponse;
                        import java.util.Optional;
                        import java.util.stream.IntStream;

                        public final class RecordingRankerFactory implements SimpleRankerFactory<String, String> {
                            @Override
                            public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
                                com.hotvect.offlineutils.commandline.DirectDomainModelJarSupportTest.ObservedLoaders.record(
                                        getClass().getClassLoader(),
                                        getClass().getClassLoader().getParent());
                                if (Boolean.getBoolean("%s")) {
                                    throw new IllegalStateException("expected setup failure");
                                }
                                return request -> RankingResponse.newResponse(
                                        IntStream.range(0, request.actions().size())
                                                .mapToObj(index -> {
                                                    var action = request.actions().get(index);
                                                    return RankingDecision.builder(action.actionId(), index, action.action()).build();
                                                })
                                                .toList());
                            }
                        }
                        """.formatted(RECORDING_FAILURE_FLAG));
        return sources;
    }

    private Path compileJar(
            String jarName,
            Map<String, String> sources,
            Map<String, String> resources,
            List<Path> extraClasspath) throws IOException {
        Path sourceRoot = tempDir.resolve(jarName + "-source");
        Path classes = tempDir.resolve(jarName + "-classes");
        Files.createDirectories(classes);
        List<String> compilerArguments = new ArrayList<>(List.of(
                "-classpath",
                compilerClasspath(extraClasspath),
                "-d",
                classes.toString()));
        for (var source : sources.entrySet()) {
            Path sourceFile = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, source.getValue());
            compilerArguments.add(sourceFile.toString());
        }
        int exitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                compilerArguments.toArray(String[]::new));
        assertEquals(0, exitCode, "fixture compilation failed");

        Path jar = tempDir.resolve(jarName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var files = Files.walk(classes)) {
                for (Path classFile : files.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(classFile).toString().replace('\\', '/')));
                    Files.copy(classFile, output);
                    output.closeEntry();
                }
            }
            for (var resource : resources.entrySet()) {
                output.putNextEntry(new JarEntry(resource.getKey()));
                output.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }

    private String compilerClasspath(List<Path> extraClasspath) {
        return Stream.concat(
                        Stream.of(System.getProperty("java.class.path")),
                        extraClasspath.stream().map(path -> path.toAbsolutePath().toString()))
                .collect(Collectors.joining(File.pathSeparator));
    }

    public static final class ObservedLoaders {
        private static final AtomicReference<ClassLoader> ALGORITHM_LOADER = new AtomicReference<>();
        private static final AtomicReference<ClassLoader> PARENT_LOADER = new AtomicReference<>();

        private ObservedLoaders() {
        }

        public static void record(ClassLoader algorithmLoader, ClassLoader parentLoader) {
            ALGORITHM_LOADER.set(algorithmLoader);
            PARENT_LOADER.set(parentLoader);
        }

        static void reset() {
            ALGORITHM_LOADER.set(null);
            PARENT_LOADER.set(null);
        }

        static ClassLoader algorithmLoader() {
            ClassLoader loader = ALGORITHM_LOADER.get();
            assertNotNull(loader);
            return loader;
        }

        static ClassLoader parentLoader() {
            ClassLoader loader = PARENT_LOADER.get();
            assertNotNull(loader);
            return loader;
        }
    }

    private record DirectFixture(Path domainModelJar, Path additionalJar, Path algorithmJar) {
    }
}
