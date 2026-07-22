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

    private CompileResult compile(String algorithmDefinition) throws IOException {
        return compile(algorithmDefinition, CATBOOST_BACKEND);
    }

    private CompileResult compile(String algorithmDefinition, String backendClass) throws IOException {
        return compile(algorithmDefinition, backendClass, false);
    }

    private CompileResult compile(String algorithmDefinition, String backendClass, boolean includeSourceOnlyBackend)
            throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path generatedDir = tempDir.resolve("generated");
        Path classesDir = tempDir.resolve("classes");
        Path packageDir = sourceDir.resolve("example");
        Files.createDirectories(packageDir);
        Files.createDirectories(generatedDir);
        Files.createDirectories(classesDir);

        Files.writeString(sourceDir.resolve("algorithm-definition.json"), algorithmDefinition, StandardCharsets.UTF_8);

        Path features = packageDir.resolve("TestFeatures.java");
        Files.writeString(features, """
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
                """, StandardCharsets.UTF_8);

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
}
