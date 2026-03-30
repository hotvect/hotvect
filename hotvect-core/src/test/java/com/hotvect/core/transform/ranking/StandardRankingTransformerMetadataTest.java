package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.CompoundNamespace;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.core.transform.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StandardRankingTransformerMetadataTest {
    static class TestShared {
        String sharedField;
    }

    static class TestAction {
        String actionField;
    }

    enum TestNamespace implements Namespace {
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
            // Just return some value type; it's not critical here.
            return RawValueType.SINGLE_STRING;
        }
    }

    @Test
    void testTransformationMetadata() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation = memoized -> "sharedTransformed";
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, true)
                .withFeature(TestNamespace.SHARED_FEATURE.getName());

        Computation<TestAction, Object> actionComputation = memoized -> "actionTransformed";
        builder.withActionComputation(TestNamespace.ACTION_COMPUTATION, actionComputation, true)
                .withFeature(TestNamespace.ACTION_FEATURE.getName());

        InteractingComputation<TestShared, TestAction, Object> interactionComputation =
                memoizedInteraction -> "interactionTransformed";
        builder.withInteractionComputation(TestNamespace.INTERACTION_COMPUTATION, interactionComputation, true)
                .withSharedComputation(TestNamespace.SHARED_FEATURE, memoizedShared -> "shared_feature_test1")
                .withActionComputation(TestNamespace.ACTION_FEATURE, memoizedAction -> "action_feature_test1")
                .withInteractionComputation(TestNamespace.INTERACTION_FEATURE, memoizedInteraction -> "interaction_feature_test1")
                .withFeature(TestNamespace.INTERACTION_FEATURE.getName());

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
        // By the original test's logic, we want this to be false:
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
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        ComputingBulkScorer<TestShared, TestAction> bulkScorer = new ComputingBulkScorer<TestShared, TestAction>() {
            @Override
            public BulkScoreResponse<TestAction> score(ComputingRankingRequest<TestShared, TestAction> rankingRequest) {
                List<com.hotvect.api.data.scoring.ScoringDecision<TestAction>> ret = new ArrayList<>();
                for (TestAction action : rankingRequest.rankingRequest().availableActions()) {
                    ret.add(com.hotvect.api.data.scoring.ScoringDecision.of(action, 1.0));
                }
                return BulkScoreResponse.of(ret, com.hotvect.api.data.FeatureStoreResponseContainer.empty());
            }

            @Override
            public BulkScoreResponse<TestAction> score(RankingRequest<TestShared, TestAction> rankingRequest) {
                List<com.hotvect.api.data.scoring.ScoringDecision<TestAction>> ret = new ArrayList<>();
                for (TestAction action : rankingRequest.availableActions()) {
                    ret.add(com.hotvect.api.data.scoring.ScoringDecision.of(action, 1.0));
                }
                return BulkScoreResponse.of(ret, com.hotvect.api.data.FeatureStoreResponseContainer.empty());
            }
        };
        builder.withBulkScorer(TestNamespace.BULK_SCORER_FEATURE, bulkScorer)
                .withFeature(TestNamespace.BULK_SCORER_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        SortedSet<Namespace> usedFeatures = transformer.getUsedFeatures();
        assertEquals(1, usedFeatures.size());
        assertTrue(usedFeatures.contains(TestNamespace.BULK_SCORER_FEATURE));
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
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, true)
                .withSharedComputation(
                        TestNamespace.SHARED_FEATURE,
                        memoized -> memoized.compute(TestNamespace.SHARED_COMPUTATION)
                )
                .withFeature(TestNamespace.SHARED_FEATURE.getName())
                .setComputationSpec(TestNamespace.SHARED_COMPUTATION, ComputationSpec.LAZY_MEMOIZED);

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        RankingRequest<TestShared, TestAction> rankingRequest =
                new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest =
                transformer.prepare(rankingRequest);

        List<com.hotvect.api.data.ranking.TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(1, transformedList.size());

        com.hotvect.api.data.common.NamespacedRecord<Namespace, Object> record = transformedList.get(0).transformed();
        // The feature should not exist if computation returned null
        assertFalse(record.asMap().containsKey(TestNamespace.SHARED_FEATURE));
    }

    @Test
    void testGetTransformationMetadataUsesReturnTypeHint() {
        Namespace namespaceWithHint = CompoundNamespace.declareNamespace(
                String.class,
                TestNamespace.SHARED_COMPUTATION,
                TestNamespace.ACTION_COMPUTATION
        );
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();
        builder.withSharedComputation(namespaceWithHint, this::sharedComputationMethod, true);

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        List<TransformationMetadata> metadataList = transformer.getTransformationMetadata();
        TransformationMetadata metadataWithHint = metadataList.stream()
                .filter(m -> m.getNamespace().equals(namespaceWithHint))
                .findFirst()
                .orElse(null);

        assertNotNull(metadataWithHint);
        assertEquals(String.class, metadataWithHint.getReturnType());
    }

    private String sharedComputationMethod(Computing<RankingRequest<TestShared, TestAction>> memoized) {
        return memoized.getOriginalInput().shared().sharedField;
    }

    @SuppressWarnings("unused")
    private Integer actionComputationMethod(Computing<TestAction> memoized) {
        return memoized.getOriginalInput().actionField.length();
    }
}
