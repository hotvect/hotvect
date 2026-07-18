package com.hotvect.catboost;

import com.hotvect.core.annotation.backend.Resolution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatBoostBackendTest {
    private final CatBoostBackend backend = new CatBoostBackend();

    @Test
    void infersFromReturnType() {
        assertEquals("com.hotvect.catboost.CatBoostFeatureType.CATEGORICAL",
                backend.resolve(null, "java.lang.String").initializerExpression());
        assertEquals("com.hotvect.catboost.CatBoostFeatureType.NUMERICAL",
                backend.resolve(null, "double").initializerExpression());
        assertEquals("com.hotvect.catboost.CatBoostFeatureType.EMBEDDING",
                backend.resolve(null, "float[]").initializerExpression());
        assertEquals("com.hotvect.catboost.CatBoostFeatureType.TEXT",
                backend.resolve(null, "java.lang.String[]").initializerExpression());
    }

    @Test
    void declaredTypeOverridesInference() {
        // String would infer CATEGORICAL; declared GROUP_ID wins.
        Resolution resolution = backend.resolve("group_id", "java.lang.String");
        assertNull(resolution.error());
        assertEquals("com.hotvect.catboost.CatBoostFeatureType.GROUP_ID", resolution.initializerExpression());
    }

    @Test
    void rejectsDeclaredTypeIncompatibleWithReturnType() {
        Resolution resolution = backend.resolve("categorical", "double");
        assertTrue(resolution.isError());
        assertTrue(resolution.error().contains("declares type 'CATEGORICAL'"), resolution.error());
    }

    @Test
    void rejectsInvalidDeclaredType() {
        assertTrue(backend.resolve("not_a_type", "double").isError());
    }

    @Test
    void rejectsUninferableReturnType() {
        Resolution resolution = backend.resolve(null, "java.util.List");
        assertTrue(resolution.isError());
    }
}
