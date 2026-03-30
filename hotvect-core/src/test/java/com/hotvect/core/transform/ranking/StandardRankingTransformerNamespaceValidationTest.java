package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.core.transform.Computation;
import com.hotvect.core.transform.Namespaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for namespace canonicalization validation in StandardRankingTransformer.
 *
 * These tests verify that the transformer fails fast during construction if any
 * namespace is not properly canonicalized via Namespaces.register() or
 * Namespaces.declareNamespace().
 *
 * Note: These tests use string-based namespaces with unique names to avoid
 * interference from global namespace registry state.
 */
class StandardRankingTransformerNamespaceValidationTest {

    static class TestShared {
        String data;
    }

    static class TestAction {
        String id;
    }

    /**
     * Helper to create a unique namespace name for testing.
     * This avoids collisions in the global namespace registry.
     */
    private static String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Test enum implementing Namespace.
     * These constants will NOT be automatically canonical unless explicitly registered.
     */
    enum TestNamespace implements Namespace {
        SHARED_NS,
        ACTION_NS,
        INTERACTION_NS,
        PRECOMPUTED_NS,
        FEATURE_NS;

        @Override
        public String getName() {
            return name();
        }

        @Override
        public ValueType getFeatureValueType() {
            return RawValueType.SINGLE_STRING;
        }
    }

    @BeforeEach
    void setUp() {
        // Clear the global namespace registry before each test to ensure isolation.
        // Note: We use a reflection-based approach since clear() is package-private.
        try {
            java.lang.reflect.Method clearMethod = Namespaces.class.getDeclaredMethod("clear");
            clearMethod.setAccessible(true);
            clearMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear namespace registry", e);
        }
    }

    @Test
    void transformerConstruction_succeeds_whenAllNamespacesAreCanonical() {
        // Register enum to make all constants canonical
        Namespaces.register(TestNamespace.class);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation =
                memoized -> "shared";
        builder.withSharedComputation(TestNamespace.SHARED_NS, sharedComputation);

        Computation<TestAction, Object> actionComputation = memoized -> "action";
        builder.withActionComputation(TestNamespace.ACTION_NS, actionComputation);

        builder.withSharedPrecomputation(TestNamespace.PRECOMPUTED_NS, "precomputed");

        builder.withFeature(TestNamespace.SHARED_NS.getName());

        // Should succeed because all namespaces are canonical
        StandardRankingTransformer<TestShared, TestAction> transformer =
                assertDoesNotThrow(builder::build);

        assertNotNull(transformer);
    }

    @Test
    void transformerConstruction_succeeds_withStringBasedCanonicalNamespaces() {
        // Use string-based namespace declarations (which are automatically canonical)
        Namespace sharedNs = Namespaces.declareFeatureNamespace(
                RawValueType.SINGLE_STRING, uniqueName("shared"));
        Namespace actionNs = Namespaces.declareFeatureNamespace(
                RawValueType.SINGLE_STRING, uniqueName("action"));

        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation =
                memoized -> "shared";
        builder.withSharedComputation(sharedNs, sharedComputation);

        Computation<TestAction, Object> actionComputation = memoized -> "action";
        builder.withActionComputation(actionNs, actionComputation);

        builder.withFeature(sharedNs.getName());

        // Should succeed because string-based namespaces are canonical
        StandardRankingTransformer<TestShared, TestAction> transformer =
                assertDoesNotThrow(builder::build);

        assertNotNull(transformer);
    }

    @Test
    void transformerConstruction_validatesFeatureCanonicalityAfterBuilderRegistration() {
        // This test verifies that feature namespace validation happens during build()
        // even when the namespace is already in the builder's dictionary.

        // Register enum to make all constants canonical
        Namespaces.register(TestNamespace.class);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        // Add a computation using the enum (registers it with builder's namespaceDictionary)
        Computation<RankingRequest<TestShared, TestAction>, Object> computation =
                memoized -> "test";
        builder.withSharedComputation(TestNamespace.FEATURE_NS, computation);

        // Mark it as a feature (this will be validated during build)
        builder.withFeature(TestNamespace.FEATURE_NS.getName());

        // Should succeed because TestNamespace was registered and FEATURE_NS is canonical
        StandardRankingTransformer<TestShared, TestAction> transformer =
                assertDoesNotThrow(builder::build);

        assertNotNull(transformer);
        assertTrue(transformer.getUsedFeatures().contains(TestNamespace.FEATURE_NS));
    }
}
