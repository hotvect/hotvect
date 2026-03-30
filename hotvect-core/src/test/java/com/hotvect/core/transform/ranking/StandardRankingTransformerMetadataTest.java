package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.CompoundNamespace;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.core.transform.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StandardRankingTransformerMetadataTest {
    private static Namespace compoundNamespaceWithHint;

    static class TestShared {
        String sharedField;
    }

    static class TestAction {
        String actionField;
    }

    enum TestNamespace implements Namespace {
        METADATA_SHARED_COMPUTATION,
        METADATA_ACTION_COMPUTATION,
        METADATA_INTERACTION_COMPUTATION,
        METADATA_SHARED_FEATURE,
        METADATA_ACTION_FEATURE,
        METADATA_INTERACTION_FEATURE,
        METADATA_BULK_SCORER_FEATURE,
        METADATA_UNKNOWN_COMPUTATION;

        @Override
        public String getName() {
            return name();
        }

        @Override
        public ValueType getFeatureValueType() {
            // Just return some value type; it's not critical here.
            return RawValueType.SINGLE_STRING;
        }
    }

    @BeforeAll
    static void registerNamespaces() {
        // Register enum to make all constants canonical
        Namespaces.register(TestNamespace.class);
        // Register compound namespace used in testGetTransformationMetadataUsesReturnTypeHint
        compoundNamespaceWithHint = Namespaces.declareNamespace(
                String.class,
                TestNamespace.METADATA_SHARED_COMPUTATION,
                TestNamespace.METADATA_ACTION_COMPUTATION
        );
    }

    @Test
    void testTransformationMetadata() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation = memoized -> "sharedTransformed";
        builder.withSharedComputation(TestNamespace.METADATA_SHARED_COMPUTATION, sharedComputation, true)
                .withFeature(TestNamespace.METADATA_SHARED_FEATURE.getName());

        Computation<TestAction, Object> actionComputation = memoized -> "actionTransformed";
        builder.withActionComputation(TestNamespace.METADATA_ACTION_COMPUTATION, actionComputation, true)
                .withFeature(TestNamespace.METADATA_ACTION_FEATURE.getName());

        InteractingComputation<TestShared, TestAction, Object> interactionComputation =
                memoizedInteraction -> "interactionTransformed";
        builder.withInteractionComputation(TestNamespace.METADATA_INTERACTION_COMPUTATION, interactionComputation, true)
                .withSharedComputation(TestNamespace.METADATA_SHARED_FEATURE, memoizedShared -> "shared_feature_test1")
                .withActionComputation(TestNamespace.METADATA_ACTION_FEATURE, memoizedAction -> "action_feature_test1")
                .withInteractionComputation(TestNamespace.METADATA_INTERACTION_FEATURE, memoizedInteraction -> "interaction_feature_test1")
                .withFeature(TestNamespace.METADATA_INTERACTION_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        List<TransformationMetadata> metadataList = transformer.getTransformationMetadata();
        assertEquals(6, metadataList.size());

        Map<Namespace, TransformationMetadata> metadataMap = new HashMap<>();
        for (TransformationMetadata metadata : metadataList) {
            metadataMap.put(metadata.getNamespace(), metadata);
            assertNotNull(metadata.getReturnType());
            assertNotNull(metadata.getBoundMethodName());
            assertNotNull(metadata.getNamespace());
        }

        TransformationMetadata sharedComputationMetadata = metadataMap.get(TestNamespace.METADATA_SHARED_COMPUTATION);
        assertNotNull(sharedComputationMetadata);
        assertFalse(sharedComputationMetadata.isEnabledAsFeature());
        assertTrue(sharedComputationMetadata.isCacheEnabled());

        TransformationMetadata sharedFeatureMetadata = metadataMap.get(TestNamespace.METADATA_SHARED_FEATURE);
        assertNotNull(sharedFeatureMetadata);
        assertTrue(sharedFeatureMetadata.isEnabledAsFeature());
        assertTrue(sharedFeatureMetadata.isCacheEnabled());

        TransformationMetadata actionComputationMetadata = metadataMap.get(TestNamespace.METADATA_ACTION_COMPUTATION);
        assertNotNull(actionComputationMetadata);
        assertFalse(actionComputationMetadata.isEnabledAsFeature());
        assertTrue(actionComputationMetadata.isCacheEnabled());

        TransformationMetadata actionFeatureMetadata = metadataMap.get(TestNamespace.METADATA_ACTION_FEATURE);
        assertNotNull(actionFeatureMetadata);
        assertTrue(actionFeatureMetadata.isEnabledAsFeature());
        // By the original test's logic, we want this to be false:
        assertFalse(actionFeatureMetadata.isCacheEnabled());

        TransformationMetadata interactionComputationMetadata = metadataMap.get(TestNamespace.METADATA_INTERACTION_COMPUTATION);
        assertNotNull(interactionComputationMetadata);
        assertFalse(interactionComputationMetadata.isEnabledAsFeature());
        assertTrue(interactionComputationMetadata.isCacheEnabled());

        TransformationMetadata interactionFeatureMetadata = metadataMap.get(TestNamespace.METADATA_INTERACTION_FEATURE);
        assertNotNull(interactionFeatureMetadata);
        assertTrue(interactionFeatureMetadata.isEnabledAsFeature());
        assertFalse(interactionFeatureMetadata.isCacheEnabled());
    }

    @Test
    void testGetUsedFeatures() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        ComputingBulkScorer<TestShared, TestAction> bulkScorer = new ComputingBulkScorer<TestShared, TestAction>() {
            @Override
            public List<com.hotvect.api.data.scoring.ScoringDecision<TestAction>> bulkScore(ComputingRankingRequest<TestShared, TestAction> rankingRequest) {
                List<com.hotvect.api.data.scoring.ScoringDecision<TestAction>> ret = new ArrayList<>();
                for (TestAction action : rankingRequest.rankingRequest().availableActions()) {
                    ret.add(com.hotvect.api.data.scoring.ScoringDecision.of(action, 1.0));
                }
                return ret;
            }

            @Override
            public List<com.hotvect.api.data.scoring.ScoringDecision<TestAction>> bulkScore(RankingRequest<TestShared, TestAction> rankingRequest) {
                List<com.hotvect.api.data.scoring.ScoringDecision<TestAction>> ret = new ArrayList<>();
                for (TestAction action : rankingRequest.availableActions()) {
                    ret.add(com.hotvect.api.data.scoring.ScoringDecision.of(action, 1.0));
                }
                return ret;
            }
        };
        builder.withBulkScorer(TestNamespace.METADATA_BULK_SCORER_FEATURE, bulkScorer)
                .withFeature(TestNamespace.METADATA_BULK_SCORER_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        SortedSet<Namespace> usedFeatures = transformer.getUsedFeatures();
        assertEquals(1, usedFeatures.size());
        assertTrue(usedFeatures.contains(TestNamespace.METADATA_BULK_SCORER_FEATURE));
    }

    @Test
    void testInvalidNamespaceInWithFeature() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();
        builder.withFeature("UNKNOWN_NAMESPACE");
        RuntimeException exception = assertThrows(RuntimeException.class, builder::build);
        assertTrue(exception.getMessage().contains("Namespace: UNKNOWN_NAMESPACE is not registered"));
    }

    @Test
    void testInvalidNamespaceInEnableCachingFor() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> builder.setComputationSpec("UNKNOWN_NAMESPACE", ComputationSpec.LAZY_MEMOIZED));
        assertTrue(exception.getMessage().contains("Namespace: UNKNOWN_NAMESPACE is not registered"));
    }

    @Test
    void testNullReturnedFromComputationIsHandled() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";

        TestAction action = new TestAction();
        action.actionField = "actionData";
        List<TestAction> actions = Collections.singletonList(action);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation = memoized -> null;
        builder.withSharedComputation(TestNamespace.METADATA_SHARED_COMPUTATION, sharedComputation, true)
                .withSharedComputation(
                        TestNamespace.METADATA_SHARED_FEATURE,
                        memoized -> memoized.compute(TestNamespace.METADATA_SHARED_COMPUTATION)
                )
                .withFeature(TestNamespace.METADATA_SHARED_FEATURE.getName())
                .setComputationSpec(TestNamespace.METADATA_SHARED_COMPUTATION, ComputationSpec.LAZY_MEMOIZED);

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        RankingRequest<TestShared, TestAction> rankingRequest =
                new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest =
                transformer.prepare(rankingRequest);

        List<com.hotvect.api.data.ranking.TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(1, transformedList.size());

        com.hotvect.api.data.common.NamespacedRecord<Namespace, Object> record = transformedList.get(0).transformed();
        // The feature should not exist if computation returned null
        assertFalse(record.asMap().containsKey(TestNamespace.METADATA_SHARED_FEATURE));
    }

    @Test
    void testGetTransformationMetadataUsesReturnTypeHint() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();
        builder.withSharedComputation(compoundNamespaceWithHint, this::sharedComputationMethod, true);

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        List<TransformationMetadata> metadataList = transformer.getTransformationMetadata();
        TransformationMetadata metadata = metadataList.stream()
                .filter(m -> m.getNamespace().equals(compoundNamespaceWithHint))
                .findFirst()
                .orElse(null);

        assertNotNull(metadata);
        assertEquals(String.class, metadata.getReturnType());
    }

    private String sharedComputationMethod(Computing<RankingRequest<TestShared, TestAction>> memoized) {
        return memoized.getOriginalInput().shared().sharedField;
    }

    @SuppressWarnings("unused")
    private Integer actionComputationMethod(Computing<TestAction> memoized) {
        return memoized.getOriginalInput().actionField.length();
    }
}
