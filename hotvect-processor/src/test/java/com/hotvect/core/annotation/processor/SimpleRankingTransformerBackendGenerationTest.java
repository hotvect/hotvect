package com.hotvect.core.annotation.processor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleRankingTransformerBackendGenerationTest {
    private static final String CATBOOST_BACKEND = "com.hotvect.catboost.CatBoostBackend.class";
    private static final String TENSORFLOW_BACKEND = "com.hotvect.tensorflow.TensorFlowBackend.class";

    @TempDir
    Path tempDir;

    @Test
    void generatesCatBoostFeatureNamespaces() throws IOException {
        String generated = compileFixture("""
                {
                  "transformer_parameters": {
                    "features": [
                      { "name": "brand", "type": "categorical" },
                      { "name": "price", "type": "numerical" },
                      { "name": "embedding", "type": "embedding" }
                    ]
                  }
                }
                """);

        assertTrue(generated.contains("CatBoostFeatureType.CATEGORICAL"));
        assertTrue(generated.contains("CatBoostFeatureType.NUMERICAL"));
        assertTrue(generated.contains("CatBoostFeatureType.EMBEDDING"));
    }

    @Test
    void generatesTensorFlowFeatureNamespacesWithMixedInferenceAndExplicitTypes() throws IOException {
        String generated = compileFixture("""
                {
                  "transformer_parameters": {
                    "features": [
                      "brand",
                      "price",
                      { "name": "embedding", "type": "float32[2]" }
                    ]
                  }
                }
                """, TENSORFLOW_BACKEND);

        assertTrue(generated.contains("TensorFlowFeatureType.STRING"));
        assertTrue(generated.contains("TensorFlowFeatureType.NUMERICAL"));
        assertTrue(generated.contains("TensorFlowFeatureType.numericalSequence(2)"));
    }

    @Test
    void infersTypesFromReturnTypesWhenAlgorithmDefinitionOmitsThem() throws IOException {
        String generated = compileFixture("""
                {
                  "transformer_parameters": {
                    "features": ["brand", "price", "embedding"]
                  }
                }
                """);

        assertTrue(generated.contains("CatBoostFeatureType.CATEGORICAL"));
        assertTrue(generated.contains("CatBoostFeatureType.NUMERICAL"));
        assertTrue(generated.contains("CatBoostFeatureType.EMBEDDING"));
    }

    @Test
    void algorithmDefinitionTypeOverridesInference() throws IOException {
        // brand returns String (would infer CATEGORICAL); GROUP_ID override wins.
        String generated = compileFixture("""
                {
                  "transformer_parameters": {
                    "features": [
                      { "name": "brand", "type": "group_id" },
                      { "name": "price", "type": "numerical" },
                      { "name": "embedding", "type": "embedding" }
                    ]
                  }
                }
                """);

        assertTrue(generated.contains("CatBoostFeatureType.GROUP_ID"));
    }

    @Test
    void rejectsDeclaredTypeIncompatibleWithReturnType() throws IOException {
        // price returns double, which is not a valid CATEGORICAL value.
        CompileResult result = compile("""
                {
                  "transformer_parameters": {
                    "features": [
                      { "name": "price", "type": "categorical" }
                    ]
                  }
                }
                """);

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("declares type 'CATEGORICAL'"), result.diagnostics());
    }

    @Test
    void rejectsBackendNotOnProcessorPath() throws IOException {
        CompileResult result = compile("""
                {
                  "transformer_parameters": {
                    "features": ["price"]
                  }
                }
                """, "example.SourceOnlyGeneratedTransformerBackend.class", true);

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("not found on the annotation processor path"), result.diagnostics());
    }

    @Test
    void rejectsBackendNotImplementingBackendInterface() throws IOException {
        CompileResult result = compile("""
                {
                  "transformer_parameters": {
                    "features": ["price"]
                  }
                }
                """, "java.lang.String.class");

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("backend"), result.diagnostics());
    }

    @Test
    void rejectsMissingBackendAnnotation() throws IOException {
        CompileResult result = compile("""
                {
                  "transformer_parameters": {
                    "features": ["price"]
                  }
                }
                """, null);

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("backend"), result.diagnostics());
    }

    @Test
    void rejectsInvalidTensorFlowType() throws IOException {
        CompileResult result = compile("""
                {
                  "transformer_parameters": {
                    "features": [
                      { "name": "embedding", "type": "float32[2][2]" }
                    ]
                  }
                }
                """, TENSORFLOW_BACKEND);

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("Invalid TensorFlow feature type"), result.diagnostics());
    }

    @Test
    void reportsCleanErrorWhenBackendThrows() throws IOException {
        CompileResult result = compile("""
                {
                  "transformer_parameters": {
                    "features": ["price"]
                  }
                }
                """, "com.hotvect.core.annotation.processor.ThrowingGeneratedTransformerBackend.class");

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("failed to resolve feature"), result.diagnostics());
    }

    @Test
    void injectAlgorithmSupportsSingletonParameters() throws IOException {
        CompileResult result = compileWithFeatures(
                """
                        {
                          "transformer_parameters": {
                            "features": ["single_policy", "generic_policy"]
                          }
                        }
                        """,
                """
                        package example;

                        import com.hotvect.api.algorithms.Algorithm;
                        import com.hotvect.core.annotation.Feature;
                        import com.hotvect.core.annotation.InjectAlgorithm;
                        import java.util.List;

                        public final class TestFeatures {
                            public interface Policy extends Algorithm {}
                            public interface GenericPolicy<T> extends Algorithm {}

                            @Feature("single_policy")
                            public static double singlePolicy(
                                    String action,
                                    @InjectAlgorithm("policy") Policy policy) {
                                return 1.0;
                            }

                            @Feature("generic_policy")
                            public static double genericPolicy(
                                    String action,
                                    @InjectAlgorithm("generic-policy") GenericPolicy<List<String>> policy) {
                                return 1.0;
                            }
                        }
                        """);

        assertTrue(result.success(), result.diagnostics());
        String generated = Files.readString(
                result.generatedDir().resolve("example").resolve("GeneratedTransformer.java"));
        assertTrue(generated.contains("TestFeatures.Policy policy"), generated);
        assertTrue(generated.contains("ALGORITHM_DEPENDENCY_POLICY_TYPE"), generated);
        assertTrue(generated.contains("ALGORITHM_DEPENDENCY_GENERICPOLICY_TYPE"), generated);
        assertTrue(generated.contains("new TypeToken<TestFeatures.Policy>() {}"), generated);
        assertTrue(generated.contains("new TypeToken<TestFeatures.GenericPolicy<List<String>>>() {}"), generated);
    }

    @Test
    void injectAlgorithmGeneratesDistinctTypeTokenConstantsWhenUppercaseNamesCollide() throws IOException {
        CompileResult result = compileWithFeatures(
                """
                        {
                          "transformer_parameters": {
                            "features": ["z_camel", "a_lower"]
                          }
                        }
                        """,
                """
                        package example;

                        import com.hotvect.api.algorithms.Algorithm;
                        import com.hotvect.core.annotation.Feature;
                        import com.hotvect.core.annotation.InjectAlgorithm;

                        public final class TestFeatures {
                            public interface CamelPolicy extends Algorithm {}
                            public interface LowerPolicy extends Algorithm {}

                            @Feature("z_camel")
                            public static double camel(
                                    String action,
                                    @InjectAlgorithm("fooBar") CamelPolicy policy) {
                                return 1.0;
                            }

                            @Feature("a_lower")
                            public static double lower(
                                    String action,
                                    @InjectAlgorithm("foobar") LowerPolicy policy) {
                                return 1.0;
                            }
                        }
                        """);

        assertTrue(result.success(), result.diagnostics());
        String generated = Files.readString(
                result.generatedDir().resolve("example").resolve("GeneratedTransformer.java"));
        assertTrue(
                generated.contains(
                        "TypeToken<TestFeatures.CamelPolicy> ALGORITHM_DEPENDENCY_FOOBAR_TYPE"),
                generated);
        assertTrue(
                generated.contains(
                        "TypeToken<TestFeatures.LowerPolicy> ALGORITHM_DEPENDENCY_FOOBAR_2_TYPE"),
                generated);
    }

    @Test
    void injectAlgorithmRejectsUnsupportedCollectionShapes() throws IOException {
        CompileResult result = compileWithFeatures(
                """
                        {
                          "transformer_parameters": {
                            "features": [
                              "raw_map",
                              "wrong_key",
                              "wildcard",
                              "nested_wildcard",
                              "type_variable",
                              "raw_generic",
                              "set"
                            ]
                          }
                        }
                        """,
                """
                        package example;

                        import com.hotvect.api.algorithms.Algorithm;
                        import com.hotvect.core.annotation.Feature;
                        import com.hotvect.core.annotation.InjectAlgorithm;
                        import java.util.Map;
                        import java.util.Set;

                        public final class TestFeatures {
                            public interface Policy extends Algorithm {}
                            public interface GenericPolicy<T> extends Algorithm {}

                            @Feature("raw_map")
                            public static double rawMap(String action, @InjectAlgorithm("raw") Map policies) {
                                return 1.0;
                            }

                            @Feature("wrong_key")
                            public static double wrongKey(
                                    String action,
                                    @InjectAlgorithm("wrong-key") Map<Integer, Policy> policies) {
                                return 1.0;
                            }

                            @Feature("wildcard")
                            public static double wildcard(
                                    String action,
                                    @InjectAlgorithm("wildcard") Map<String, ? extends Policy> policies) {
                                return 1.0;
                            }

                            @Feature("nested_wildcard")
                            public static double nestedWildcard(
                                    String action,
                                    @InjectAlgorithm("nested-wildcard") Map<String, GenericPolicy<?>> policies) {
                                return 1.0;
                            }

                            @Feature("type_variable")
                            public static <T extends Policy> double typeVariable(
                                    String action,
                                    @InjectAlgorithm("type-variable") T policy) {
                                return 1.0;
                            }

                            @Feature("raw_generic")
                            public static double rawGeneric(
                                    String action,
                                    @InjectAlgorithm("raw-generic") GenericPolicy policy) {
                                return 1.0;
                            }

                            @Feature("set")
                            public static double set(String action, @InjectAlgorithm("set") Set<Policy> policies) {
                                return 1.0;
                            }
                        }
                        """);

        assertFalse(result.success());
        assertTrue(
                result.diagnostics().contains(
                        "@InjectAlgorithm parameter type must be a concrete Algorithm"),
                result.diagnostics());
    }

    @Test
    void injectAlgorithmRejectsMapParameters() throws IOException {
        CompileResult result = compileWithFeatures(
                """
                        {
                          "transformer_parameters": {
                            "features": ["policy_set"]
                          }
                        }
                        """,
                """
                        package example;

                        import com.hotvect.api.algorithms.Algorithm;
                        import com.hotvect.core.annotation.Feature;
                        import com.hotvect.core.annotation.InjectAlgorithm;
                        import java.util.Map;

                        public final class TestFeatures {
                            public interface Policy extends Algorithm {}

                            @Feature("policy_set")
                            public static double policySet(
                                    String action,
                                    @InjectAlgorithm("policy") Map<String, Policy> policies) {
                                return policies.size();
                            }
                        }
                        """);

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains(
                "@InjectAlgorithm parameter type must be a concrete Algorithm"),
                result.diagnostics());
    }

    @Test
    void injectAlgorithmRejectsDeeplyNestedTypeVariables() throws IOException {
        CompileResult result = compileWithFeatures(
                """
                        {
                          "transformer_parameters": {
                            "features": ["array_type_variable", "owner_type_variable"]
                          }
                        }
                        """,
                """
                        package example;

                        import com.hotvect.api.algorithms.Algorithm;
                        import com.hotvect.core.annotation.Feature;
                        import com.hotvect.core.annotation.InjectAlgorithm;

                        public final class TestFeatures {
                            public interface GenericPolicy<T> extends Algorithm {}

                            public static final class GenericOwner<T> {
                                public final class Policy implements Algorithm {}
                            }

                            @Feature("array_type_variable")
                            public static <T> double arrayTypeVariable(
                                    String action,
                                    @InjectAlgorithm("array-type-variable") GenericPolicy<T[]> policy) {
                                return 1.0;
                            }

                            @Feature("owner_type_variable")
                            public static <T> double ownerTypeVariable(
                                    String action,
                                    @InjectAlgorithm("owner-type-variable") GenericOwner<T>.Policy policy) {
                                return 1.0;
                            }
                        }
                        """);

        assertFalse(result.success());
        assertTrue(
                result.diagnostics().contains(
                        "@InjectAlgorithm parameter type must be a concrete Algorithm"),
                result.diagnostics());
    }

    @Test
    void injectAlgorithmAcceptsStaticNestedTypeInsideGenericOwner() throws IOException {
        CompileResult result = compileWithFeatures(
                """
                        {
                          "transformer_parameters": {
                            "features": ["static_nested_policy"]
                          }
                        }
                        """,
                """
                        package example;

                        import com.hotvect.api.algorithms.Algorithm;
                        import com.hotvect.core.annotation.Feature;
                        import com.hotvect.core.annotation.InjectAlgorithm;
                        import java.util.List;

                        public final class TestFeatures {
                            public static final class GenericOwner<T> {
                                public static final class Policy<U> implements Algorithm {}
                            }

                            @Feature("static_nested_policy")
                            public static double staticNestedPolicy(
                                    String action,
                                    @InjectAlgorithm("static-nested-policy")
                                    GenericOwner.Policy<List<String>> policy) {
                                return 1.0;
                            }
                        }
                        """);

        assertTrue(result.success(), result.diagnostics());
        String generated = Files.readString(
                result.generatedDir().resolve("example").resolve("GeneratedTransformer.java"));
        assertTrue(
                generated.contains("new TypeToken<TestFeatures.GenericOwner.Policy<List<String>>>() {}"),
                generated);
    }

    private CompileResult compile(String algorithmDefinition) throws IOException {
        return compile(algorithmDefinition, CATBOOST_BACKEND);
    }

    private CompileResult compile(String algorithmDefinition, String backendClass) throws IOException {
        return compile(algorithmDefinition, backendClass, false);
    }

    private CompileResult compile(String algorithmDefinition, String backendClass, boolean includeSourceOnlyBackend)
            throws IOException {
        return compile(algorithmDefinition, backendClass, includeSourceOnlyBackend, defaultFeatureSource());
    }

    private CompileResult compileWithFeatures(String algorithmDefinition, String featureSource) throws IOException {
        return compile(algorithmDefinition, CATBOOST_BACKEND, false, featureSource);
    }

    private CompileResult compile(
            String algorithmDefinition,
            String backendClass,
            boolean includeSourceOnlyBackend,
            String featureSource) throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path generatedDir = tempDir.resolve("generated");
        Path classesDir = tempDir.resolve("classes");
        Path packageDir = sourceDir.resolve("example");
        Files.createDirectories(packageDir);
        Files.createDirectories(generatedDir);
        Files.createDirectories(classesDir);

        Files.writeString(sourceDir.resolve("algorithm-definition.json"), algorithmDefinition, StandardCharsets.UTF_8);

        Path features = packageDir.resolve("TestFeatures.java");
        Files.writeString(features, featureSource, StandardCharsets.UTF_8);

        Path sourceOnlyBackend = packageDir.resolve("SourceOnlyGeneratedTransformerBackend.java");
        if (includeSourceOnlyBackend) {
            Files.writeString(sourceOnlyBackend, """
                    package example;

                    import com.hotvect.core.annotation.backend.GeneratedTransformerBackend;
                    import com.hotvect.core.annotation.backend.Resolution;

                    public final class SourceOnlyGeneratedTransformerBackend implements GeneratedTransformerBackend {
                        @Override
                        public Resolution resolve(String declaredType, String returnTypeName) {
                            return Resolution.error("unused");
                        }
                    }
                    """, StandardCharsets.UTF_8);
        }

        Path spec = packageDir.resolve("GeneratedTransformerFactory.java");
        String backendArgument = backendClass == null ? "" : "        backend = " + backendClass + ",\n";
        Files.writeString(spec, """
                package example;

                import com.hotvect.core.annotation.GenerateSimpleRankingTransformer;

                @GenerateSimpleRankingTransformer(
                        name = "GeneratedTransformer",
                        sharedType = String.class,
                        actionType = String.class,
                        features = {TestFeatures.class},
                %s\
                        algorithmDefinitionResource = "algorithm-definition.json"
                )
                public final class GeneratedTransformerFactory {
                }
                """.formatted(backendArgument), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for annotation-processor tests");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<java.io.File> sourceFiles = new ArrayList<>(List.of(features.toFile(), spec.toFile()));
            if (includeSourceOnlyBackend) {
                sourceFiles.add(sourceOnlyBackend.toFile());
            }
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", System.getProperty("java.class.path"),
                    "-processorpath", System.getProperty("java.class.path"),
                    "-processor", SimpleRankingTransformerProcessor.class.getCanonicalName(),
                    "-sourcepath", sourceDir.toString(),
                    "-s", generatedDir.toString(),
                    "-d", classesDir.toString()
            );
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            return new CompileResult(Boolean.TRUE.equals(success), formatDiagnostics(diagnostics), generatedDir);
        }
    }

    private record CompileResult(boolean success, String diagnostics, Path generatedDir) {}

    private String compileFixture(String algorithmDefinition) throws IOException {
        return compileFixture(algorithmDefinition, CATBOOST_BACKEND);
    }

    private String compileFixture(String algorithmDefinition, String backendClass) throws IOException {
        CompileResult result = compile(algorithmDefinition, backendClass);
        assertTrue(result.success(), result.diagnostics());
        return Files.readString(result.generatedDir().resolve("example").resolve("GeneratedTransformer.java"));
    }

    private String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder message = new StringBuilder("Compilation failed");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            message.append("\n")
                    .append(diagnostic.getKind())
                    .append(": ")
                    .append(diagnostic.getMessage(Locale.ROOT));
        }
        return message.toString();
    }

    private static String defaultFeatureSource() {
        return """
                package example;

                import com.hotvect.core.annotation.Feature;

                public final class TestFeatures {
                    private TestFeatures() {}

                    @Feature("brand")
                    public static String brand(String action) {
                        return action;
                    }

                    @Feature("price")
                    public static double price(String action) {
                        return 1.0;
                    }

                    @Feature("embedding")
                    public static float[] embedding(String action) {
                        return new float[] {1.0f, 2.0f};
                    }
                }
                """;
    }
}
