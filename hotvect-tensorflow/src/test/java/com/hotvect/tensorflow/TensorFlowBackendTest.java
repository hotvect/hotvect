package com.hotvect.tensorflow;

import com.hotvect.core.annotation.backend.Resolution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TensorFlowBackendTest {
    private final TensorFlowBackend backend = new TensorFlowBackend();

    @Test
    void infersScalarsFromReturnType() {
        assertEquals("com.hotvect.tensorflow.TensorFlowFeatureType.CATEGORICAL",
                backend.resolve(null, "long").initializerExpression());
        assertEquals("com.hotvect.tensorflow.TensorFlowFeatureType.NUMERICAL",
                backend.resolve(null, "double").initializerExpression());
        assertEquals("com.hotvect.tensorflow.TensorFlowFeatureType.STRING",
                backend.resolve(null, "java.lang.String").initializerExpression());
    }

    @Test
    void parsesDeclaredSequenceType() {
        Resolution resolution = backend.resolve("float32[768]", "float[]");
        assertNull(resolution.error());
        assertEquals("com.hotvect.tensorflow.TensorFlowFeatureType.numericalSequence(768)",
                resolution.initializerExpression());
        assertEquals("com.hotvect.tensorflow.TensorFlowFeatureType.categoricalSequence(50)",
                backend.resolve("int64[50]", "int[]").initializerExpression());
    }

    @Test
    void cannotInferArrayWithoutDeclaredLength() {
        assertTrue(backend.resolve(null, "float[]").isError());
    }

    @Test
    void rejectsReturnTypeIncompatibleWithDeclaredType() {
        Resolution resolution = backend.resolve("float32", "java.lang.String");
        assertTrue(resolution.isError());
        assertTrue(resolution.error().contains("declares type 'float32'"), resolution.error());
    }

    @Test
    void rejectsInvalidDeclaredType() {
        assertTrue(backend.resolve("float32[2][2]", "float[]").isError());
        assertTrue(backend.resolve("float64", "double").isError());
        assertTrue(backend.resolve("string[3]", "java.lang.String[]").isError());
    }
}
