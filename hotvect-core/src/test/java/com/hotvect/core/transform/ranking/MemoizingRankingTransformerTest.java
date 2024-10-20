package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.*;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.memoization.*;
import com.hotvect.api.transformation.ranking.MemoizableBulkScorer;
import com.hotvect.api.transformation.ranking.MemoizedRankingRequest;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MemoizingRankingTransformerTest {
    // Define test SHARED and ACTION types
    static class TestShared {
        String sharedField;
    }

    static class TestAction {
        String actionField;
    }

    // Define test Namespaces
    enum TestNamespace implements Namespace, FeatureNamespace {
        SHARED_COMPUTATION,
        ACTION_COMPUTATION,
        INTERACTION_COMPUTATION,
        SHARED_FEATURE,
        ACTION_FEATURE,
        INTERACTION_FEATURE,
        BULK_SCORER_FEATURE,
        UNKNOWN_COMPUTATION;

        @Override
        public String getName() {
            return name();
        }

        @Override
        public ValueType getFeatureValueType() {
            return RawValueType.SINGLE_STRING;
        }
    }

    @Test
    void testApplyWithMemoizedComputations() {
        // Initialize shared and action data
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        TestAction action2 = new TestAction();
        action2.actionField = "actionData2";
        List<TestAction> actions = Arrays.asList(action1, action2);

        // Initialize transformer builder
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();

        // Define memoized shared computation
        AtomicInteger sharedCounter = new AtomicInteger(0);
        Computation<TestShared, RawValue> sharedComputation = memoized -> {
            sharedCounter.incrementAndGet();
            return RawValue.singleString(memoized.getOriginalInput().sharedField + "-transformed");
        };
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, true);

        // Define shared feature computation
        Computation<TestShared, RawValue> sharedFeatureComputation = memoized -> (RawValue) memoized.computeIfAbsent(TestNamespace.SHARED_COMPUTATION);
        builder.withSharedComputation(TestNamespace.SHARED_FEATURE, sharedFeatureComputation, true);

        // Indicate that SHARED_FEATURE is to be used as a feature
        builder.withFeature(TestNamespace.SHARED_FEATURE.getName());

        // Define memoized action computation
        AtomicInteger actionCounter = new AtomicInteger(0);
        Computation<TestAction, RawValue> actionComputation = memoized -> {
            actionCounter.incrementAndGet();
            return RawValue.singleString(memoized.getOriginalInput().actionField + "-transformed");
        };
        builder.withActionComputation(TestNamespace.ACTION_COMPUTATION, actionComputation, true);

        // Define action feature computation
        Computation<TestAction, RawValue> actionFeatureComputation = memoized -> (RawValue) memoized.computeIfAbsent(TestNamespace.ACTION_COMPUTATION);
        builder.withActionComputation(TestNamespace.ACTION_FEATURE, actionFeatureComputation, true);

        // Indicate that ACTION_FEATURE is to be used as a feature
        builder.withFeature(TestNamespace.ACTION_FEATURE.getName());

        // Define memoized interaction computation
        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<TestShared, TestAction, RawValue> interactionComputation = memoizedInteraction -> {
            interactionCounter.incrementAndGet();
            String sharedResult = ((RawValue) memoizedInteraction.getShared().computeIfAbsent(TestNamespace.SHARED_COMPUTATION)).getSingleString();
            String actionResult = ((RawValue) memoizedInteraction.getAction().computeIfAbsent(TestNamespace.ACTION_COMPUTATION)).getSingleString();
            return RawValue.singleString(sharedResult + "-" + actionResult + "-interactionTransformed");
        };
        builder.withInteractionComputation(TestNamespace.INTERACTION_COMPUTATION, interactionComputation, true);

        // Define interaction feature computation
        InteractingComputation<TestShared, TestAction, RawValue> interactionFeatureComputation = memoizedInteraction -> (RawValue) memoizedInteraction.computeIfAbsent(TestNamespace.INTERACTION_COMPUTATION);
        builder.withInteractionComputation(TestNamespace.INTERACTION_FEATURE, interactionFeatureComputation, true);

        // Indicate that INTERACTION_FEATURE is to be used as a feature
        builder.withFeature(TestNamespace.INTERACTION_FEATURE.getName());

        // Build the transformer
        MemoizingRankingTransformer<TestShared, TestAction> transformer = builder.build();

        // Create RankingRequest
        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);

        // Memoize the RankingRequest
        MemoizedRankingRequest<TestShared, TestAction> memoizedRankingRequest = transformer.memoize(rankingRequest);

        // Apply the transformer
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformedList = transformer.apply(memoizedRankingRequest);

        // Assertions
        assertEquals(2, transformedList.size());
        for (NamespacedRecord<FeatureNamespace, RawValue> transformed : transformedList) {
            // Check that features are present
            assertTrue(transformed.asMap().containsKey(TestNamespace.SHARED_FEATURE));
            assertTrue(transformed.asMap().containsKey(TestNamespace.ACTION_FEATURE));
            assertTrue(transformed.asMap().containsKey(TestNamespace.INTERACTION_FEATURE));

            // Verify the transformed values
            String sharedFeatureValue = transformed.get(TestNamespace.SHARED_FEATURE).getSingleString();
            String actionFeatureValue = transformed.get(TestNamespace.ACTION_FEATURE).getSingleString();
            String interactionFeatureValue = transformed.get(TestNamespace.INTERACTION_FEATURE).getSingleString();
            assertEquals("sharedData-transformed", sharedFeatureValue);
            assertTrue(actionFeatureValue.startsWith("actionData"));
            assertTrue(actionFeatureValue.endsWith("-transformed"));
            assertEquals(sharedFeatureValue + "-" + actionFeatureValue + "-interactionTransformed", interactionFeatureValue);
        }

        // Verify counters
        assertEquals(1, sharedCounter.get(), "Shared computation should have been called once");
        assertEquals(2, actionCounter.get(), "Action computation should have been called for each action");
        assertEquals(2, interactionCounter.get(), "Interaction computation should have been called for each action");

        // Call apply again to check memoization
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformedList2 = transformer.apply(memoizedRankingRequest);

        // Counters should not increase
        assertEquals(1, sharedCounter.get(), "Shared computation should not have been called again");
        assertEquals(2, actionCounter.get(), "Action computation should not have been called again");
        assertEquals(2, interactionCounter.get(), "Interaction computation should not have been called again");
    }

    @Test
    void testApplyWithNonMemoizedComputations() {
        // Initialize shared and action data
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        TestAction action2 = new TestAction();
        action2.actionField = "actionData2";
        List<TestAction> actions = Arrays.asList(action1, action2);

        // Initialize transformer builder
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();

        // Define non-memoized shared computation
        AtomicInteger sharedCounter = new AtomicInteger(0);
        Computation<TestShared, RawValue> sharedComputation = memoized -> {
            sharedCounter.incrementAndGet();
            return RawValue.singleString(memoized.getOriginalInput().sharedField + "-transformed");
        };
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, false)
                .withSharedComputation(TestNamespace.SHARED_FEATURE, memoized -> memoized.computeIfAbsent(TestNamespace.SHARED_COMPUTATION))
                .withFeature(TestNamespace.SHARED_FEATURE.getName());

        // Define non-memoized action computation
        AtomicInteger actionCounter = new AtomicInteger(0);
        Computation<TestAction, RawValue> actionComputation = memoized -> {
            actionCounter.incrementAndGet();
            return RawValue.singleString(memoized.getOriginalInput().actionField + "-transformed");
        };
        builder.withActionComputation(TestNamespace.ACTION_COMPUTATION, actionComputation, false)
                .withActionComputation(TestNamespace.ACTION_FEATURE, memoized -> memoized.computeIfAbsent(TestNamespace.ACTION_COMPUTATION))
                .withFeature(TestNamespace.ACTION_FEATURE.getName());

        // Define non-memoized interaction computation
        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<TestShared, TestAction, RawValue> interactionComputation = memoizedInteraction -> {
            interactionCounter.incrementAndGet();
            String sharedResult = ((RawValue) memoizedInteraction.getShared().computeIfAbsent(TestNamespace.SHARED_COMPUTATION)).getSingleString();
            String actionResult = ((RawValue) memoizedInteraction.getAction().computeIfAbsent(TestNamespace.ACTION_COMPUTATION)).getSingleString();
            return RawValue.singleString(sharedResult + "-" + actionResult + "-interactionTransformed");
        };
        builder.withInteractionComputation(TestNamespace.INTERACTION_COMPUTATION, interactionComputation, false)
                .withInteractionComputation(TestNamespace.INTERACTION_FEATURE, memoized -> memoized.computeIfAbsent(TestNamespace.INTERACTION_COMPUTATION))
                .withFeature(TestNamespace.INTERACTION_FEATURE.getName());

        // Build the transformer
        MemoizingRankingTransformer<TestShared, TestAction> transformer = builder.build();

        // Create RankingRequest
        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);

        // Memoize the RankingRequest
        MemoizedRankingRequest<TestShared, TestAction> memoizedRankingRequest = transformer.memoize(rankingRequest);

        // Apply the transformer
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformedList = transformer.apply(memoizedRankingRequest);

        // Assertions
        assertEquals(2, transformedList.size());

        // Verify counters
        assertEquals(3, sharedCounter.get(), "Shared computation should have been called once for shared, and then for each action");
        assertEquals(4, actionCounter.get(), "Action computation should have been called for each action twice (once for action, once for interaction)");
        assertEquals(2, interactionCounter.get(), "Interaction computation should have been called for each action");

        // Call apply again to check non-memoization
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformedList2 = transformer.apply(memoizedRankingRequest);

        // Counters should increase
        assertEquals(5, sharedCounter.get(), "Shared computation should have been called again for each action, since the shared feature is not cached (i.e., 3+2)");
        assertEquals(8, actionCounter.get(), "Action computation should have been called again for each action");
        assertEquals(4, interactionCounter.get(), "Interaction computation should have been called again for each action");
    }

    @Test
    void testBulkScorer() {
        // Initialize shared and action data
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        TestAction action2 = new TestAction();
        action2.actionField = "actionData2";
        List<TestAction> actions = Arrays.asList(action1, action2);

        // Initialize transformer builder
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();

        // Define a bulk scorer
        MemoizableBulkScorer<TestShared, TestAction> bulkScorer = new MemoizableBulkScorer<>() {
            @Override
            public DoubleList apply(RankingRequest<TestShared, TestAction> rankingRequest) {
                int size = rankingRequest.getAvailableActions().size();
                double[] score = new double[size];
                Arrays.fill(score, 42);
                return new DoubleArrayList(score);
            }

            @Override
            public DoubleList apply(MemoizedRankingRequest<TestShared, TestAction> rankingRequest) {
                int size = rankingRequest.getAction().size();
                double[] score = new double[size];
                Arrays.fill(score, 42);
                return new DoubleArrayList(score);
            }
        };
        builder.withBulkScorer(TestNamespace.BULK_SCORER_FEATURE, bulkScorer)
                .withFeature(TestNamespace.BULK_SCORER_FEATURE.getName());

        // Build the transformer
        MemoizingRankingTransformer<TestShared, TestAction> transformer = builder.build();

        // Create RankingRequest
        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);

        // Memoize the RankingRequest
        MemoizedRankingRequest<TestShared, TestAction> memoizedRankingRequest = transformer.memoize(rankingRequest);

        // Apply the transformer
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformedList = transformer.apply(memoizedRankingRequest);

        // Assertions
        assertEquals(2, transformedList.size());
        for (NamespacedRecord<FeatureNamespace, RawValue> transformed : transformedList) {
            assertTrue(transformed.asMap().containsKey(TestNamespace.BULK_SCORER_FEATURE));
            double score = transformed.get(TestNamespace.BULK_SCORER_FEATURE).getSingleNumerical();
            assertEquals(42.0, score);
        }
    }

    @Test
    void testMissingComputationThrowsException() {
        // Initialize transformer builder
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();

        // Try to register a feature without adding a corresponding computation
        builder.withFeature(TestNamespace.SHARED_FEATURE.getName());

        // Build the transformer and expect an exception
        NamespaceNotFoundException exception = assertThrows(NamespaceNotFoundException.class, builder::build);
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("Namespace: " + TestNamespace.SHARED_FEATURE.getName() + " is not registered"));
    }

    @Test
    void testTransformationMetadata() {
        // Initialize transformer builder
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();
        Computation<TestShared, RawValue> sharedComputation = memoized -> RawValue.singleString("sharedTransformed");
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, true)
                .withFeature(TestNamespace.SHARED_FEATURE.getName());
        Computation<TestAction, RawValue> actionComputation = memoized -> RawValue.singleString("actionTransformed");
        builder.withActionComputation(TestNamespace.ACTION_COMPUTATION, actionComputation, true)
                .withFeature(TestNamespace.ACTION_FEATURE.getName());
        InteractingComputation<TestShared, TestAction, RawValue> interactionComputation = memoizedInteraction -> RawValue.singleString("interactionTransformed");
        builder.withInteractionComputation(TestNamespace.INTERACTION_COMPUTATION, interactionComputation, true)
                .withSharedComputation(TestNamespace.SHARED_FEATURE, memoizedShared -> "shared_feature_test1")
                .withActionComputation(TestNamespace.ACTION_FEATURE, memoizedAction -> "action_feature_test1")
                .withInteractionComputation(TestNamespace.INTERACTION_FEATURE, memoizedInteraction -> "interaction_feature_test1")
                .withFeature(TestNamespace.INTERACTION_FEATURE.getName());

        // Build the transformer
        MemoizingRankingTransformer<TestShared, TestAction> transformer = builder.build();

        List<TransformationMetadata> metadataList = transformer.getTransformationMetadata();
        assertEquals(6, metadataList.size());
        Map<Namespace, TransformationMetadata> metadataMap = new HashMap<>();
        for (TransformationMetadata metadata : metadataList) {
            metadataMap.put(metadata.getNamespace(), metadata);
            assertNotNull(metadata.getReturnType());
            assertNotNull(metadata.getBoundMethodName());
            assertNotNull(metadata.getNamespace());
        }

        TransformationMetadata sharedComputationMetadata = metadataMap.get(TestNamespace.SHARED_COMPUTATION);
        assertNotNull(sharedComputationMetadata);
        assertFalse(sharedComputationMetadata.isEnabledAsFeature());
        assertTrue(sharedComputationMetadata.isCacheEnabled());

        TransformationMetadata sharedFeatureMetadata = metadataMap.get(TestNamespace.SHARED_FEATURE);
        assertNotNull(sharedFeatureMetadata);
        assertTrue(sharedFeatureMetadata.isEnabledAsFeature());
        assertTrue(sharedFeatureMetadata.isCacheEnabled());

        TransformationMetadata actionComputationMetadata = metadataMap.get(TestNamespace.ACTION_COMPUTATION);
        assertNotNull(actionComputationMetadata);
        assertFalse(actionComputationMetadata.isEnabledAsFeature());
        assertTrue(actionComputationMetadata.isCacheEnabled());

        TransformationMetadata actionFeatureMetadata = metadataMap.get(TestNamespace.ACTION_FEATURE);
        assertNotNull(actionFeatureMetadata);
        assertTrue(actionFeatureMetadata.isEnabledAsFeature());
        assertFalse(actionFeatureMetadata.isCacheEnabled());

        TransformationMetadata interactionComputationMetadata = metadataMap.get(TestNamespace.INTERACTION_COMPUTATION);
        assertNotNull(interactionComputationMetadata);
        assertFalse(interactionComputationMetadata.isEnabledAsFeature());
        assertTrue(interactionComputationMetadata.isCacheEnabled());

        TransformationMetadata interactionFeatureMetadata = metadataMap.get(TestNamespace.INTERACTION_FEATURE);
        assertNotNull(interactionFeatureMetadata);
        assertTrue(interactionFeatureMetadata.isEnabledAsFeature());
        assertFalse(interactionFeatureMetadata.isCacheEnabled());
    }

    @Test
    void testGetUsedFeatures() {
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();
        MemoizableBulkScorer<TestShared, TestAction> bulkScorer = new MemoizableBulkScorer<>() {
            @Override
            public DoubleList apply(RankingRequest<TestShared, TestAction> rankingRequest) {
                return new DoubleArrayList(new double[]{1.0});
            }

            @Override
            public DoubleList apply(MemoizedRankingRequest<TestShared, TestAction> rankingRequest) {
                return new DoubleArrayList(new double[]{1.0});
            }
        };
        builder.withBulkScorer(TestNamespace.BULK_SCORER_FEATURE, bulkScorer)
                .withFeature(TestNamespace.BULK_SCORER_FEATURE.getName());
        MemoizingRankingTransformer<TestShared, TestAction> transformer = builder.build();
        SortedSet<FeatureNamespace> usedFeatures = transformer.getUsedFeatures();
        assertEquals(1, usedFeatures.size());
        assertTrue(usedFeatures.contains(TestNamespace.BULK_SCORER_FEATURE));
    }

    @Test
    void testInvalidNamespaceInWithFeature() {
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();
        builder.withFeature("UNKNOWN_NAMESPACE");
        RuntimeException exception = assertThrows(RuntimeException.class, builder::build);
        assertTrue(exception.getMessage().contains("Namespace: UNKNOWN_NAMESPACE is not registered"));
    }

    @Test
    void testInvalidNamespaceInEnableCachingFor() {
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();
        RuntimeException exception = assertThrows(RuntimeException.class, () -> builder.enableCachingFor("UNKNOWN_NAMESPACE"));
        assertTrue(exception.getMessage().contains("Namespace: UNKNOWN_NAMESPACE is not registered"));
    }

    @Test
    void testNullReturnedFromComputationIsHandled() {
        // Initialize shared and action data
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action = new TestAction();
        action.actionField = "actionData";
        List<TestAction> actions = Collections.singletonList(action);

        // Initialize transformer builder
        MemoizingRankingTransformer.Builder<TestShared, TestAction> builder = MemoizingRankingTransformer.builder();

        // Define a computation that returns null
        Computation<TestShared, RawValue> sharedComputation = memoized -> null;
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, true)
                .withSharedComputation(TestNamespace.SHARED_FEATURE, memoized -> memoized.computeIfAbsent(TestNamespace.SHARED_COMPUTATION))
                .withFeature(TestNamespace.SHARED_FEATURE.getName())
                .enableCachingFor(TestNamespace.SHARED_COMPUTATION);

        // Build the transformer
        MemoizingRankingTransformer<TestShared, TestAction> transformer = builder.build();

        // Create RankingRequest
        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);

        // Memoize the RankingRequest
        MemoizedRankingRequest<TestShared, TestAction> memoizedRankingRequest = transformer.memoize(rankingRequest);

        // Apply the transformer
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformedList = transformer.apply(memoizedRankingRequest);

        // Assertions
        assertEquals(1, transformedList.size());
        NamespacedRecord<FeatureNamespace, RawValue> transformed = transformedList.get(0);

        // The feature should not be present since the computation returned null
        assertFalse(transformed.asMap().containsKey(TestNamespace.SHARED_FEATURE));
    }
}