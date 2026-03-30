package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.core.transform.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StandardRankingTransformerBasicTest {
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
    void testApplyWithMemoizedComputations() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        TestAction action2 = new TestAction();
        action2.actionField = "actionData2";
        List<TestAction> actions = Arrays.asList(action1, action2);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        AtomicInteger sharedCounter = new AtomicInteger(0);
        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation = memoized -> {
            sharedCounter.incrementAndGet();
            return memoized.getOriginalInput().shared().sharedField + "-transformed";
        };
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, true);

        Computation<RankingRequest<TestShared, TestAction>, Object> sharedFeatureComputation = memoized -> memoized.compute(TestNamespace.SHARED_COMPUTATION);
        builder.withSharedComputation(TestNamespace.SHARED_FEATURE, sharedFeatureComputation, true);

        builder.withFeature(TestNamespace.SHARED_FEATURE.getName());

        AtomicInteger actionCounter = new AtomicInteger(0);
        Computation<TestAction, Object> actionComputation = memoized -> {
            actionCounter.incrementAndGet();
            return memoized.getOriginalInput().actionField + "-transformed";
        };
        builder.withActionComputation(TestNamespace.ACTION_COMPUTATION, actionComputation, true);

        Computation<TestAction, Object> actionFeatureComputation = memoized -> memoized.compute(TestNamespace.ACTION_COMPUTATION);
        builder.withActionComputation(TestNamespace.ACTION_FEATURE, actionFeatureComputation, true);
        builder.withFeature(TestNamespace.ACTION_FEATURE.getName());

        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<TestShared, TestAction, Object> interactionComputation = memoizedInteraction -> {
            interactionCounter.incrementAndGet();
            String sharedResult = (String) memoizedInteraction.getShared().compute(TestNamespace.SHARED_COMPUTATION);
            String actionResult = (String) memoizedInteraction.getAction().compute(TestNamespace.ACTION_COMPUTATION);
            return sharedResult + "-" + actionResult + "-interactionTransformed";
        };
        builder.withInteractionComputation(TestNamespace.INTERACTION_COMPUTATION, interactionComputation, true);

        InteractingComputation<TestShared, TestAction, Object> interactionFeatureComputation =
                memoizedInteraction -> memoizedInteraction.compute(TestNamespace.INTERACTION_COMPUTATION);
        builder.withInteractionComputation(TestNamespace.INTERACTION_FEATURE, interactionFeatureComputation, true);

        builder.withFeature(TestNamespace.INTERACTION_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        // Now use transform instead of apply
        List<TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(2, transformedList.size());

        for (TransformedAction<TestAction> ta : transformedList) {
            NamespacedRecord<Namespace, Object> record = ta.transformed();
            assertTrue(record.asMap().containsKey(TestNamespace.SHARED_FEATURE));
            assertTrue(record.asMap().containsKey(TestNamespace.ACTION_FEATURE));
            assertTrue(record.asMap().containsKey(TestNamespace.INTERACTION_FEATURE));

            String sharedFeatureValue = (String) record.get(TestNamespace.SHARED_FEATURE);
            String actionFeatureValue = (String) record.get(TestNamespace.ACTION_FEATURE);
            String interactionFeatureValue = (String) record.get(TestNamespace.INTERACTION_FEATURE);

            assertEquals("sharedData-transformed", sharedFeatureValue);
            assertTrue(actionFeatureValue.startsWith("actionData"));
            assertTrue(actionFeatureValue.endsWith("-transformed"));
            assertEquals(sharedFeatureValue + "-" + actionFeatureValue + "-interactionTransformed",
                    interactionFeatureValue);
        }
        // Counters for memoized
        assertEquals(1, sharedCounter.get());
        assertEquals(2, actionCounter.get());
        assertEquals(2, interactionCounter.get());

        // Another transform call => no increments
        List<TransformedAction<TestAction>> transformedList2 = transformer.transform(computingRankingRequest);
        assertEquals(2, transformedList2.size());
        assertEquals(1, sharedCounter.get());
        assertEquals(2, actionCounter.get());
        assertEquals(2, interactionCounter.get());
    }

    @Test
    void testApplyWithNonMemoizedComputations() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        TestAction action2 = new TestAction();
        action2.actionField = "actionData2";
        List<TestAction> actions = Arrays.asList(action1, action2);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        AtomicInteger sharedCounter = new AtomicInteger(0);
        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation = memoized -> {
            sharedCounter.incrementAndGet();
            return memoized.getOriginalInput().shared().sharedField + "-transformed";
        };
        builder.withSharedComputation(TestNamespace.SHARED_COMPUTATION, sharedComputation, false)
                .withSharedComputation(TestNamespace.SHARED_FEATURE, memoized -> memoized.compute(TestNamespace.SHARED_COMPUTATION))
                .withFeature(TestNamespace.SHARED_FEATURE.getName());

        AtomicInteger actionCounter = new AtomicInteger(0);
        Computation<TestAction, Object> actionComputation = memoized -> {
            actionCounter.incrementAndGet();
            return memoized.getOriginalInput().actionField + "-transformed";
        };
        builder.withActionComputation(TestNamespace.ACTION_COMPUTATION, actionComputation, false)
                .withActionComputation(TestNamespace.ACTION_FEATURE, memoized -> memoized.compute(TestNamespace.ACTION_COMPUTATION))
                .withFeature(TestNamespace.ACTION_FEATURE.getName());

        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<TestShared, TestAction, Object> interactionComputation = memoizedInteraction -> {
            interactionCounter.incrementAndGet();
            String sharedResult = (String) memoizedInteraction.getShared().compute(TestNamespace.SHARED_COMPUTATION);
            String actionResult = (String) memoizedInteraction.getAction().compute(TestNamespace.ACTION_COMPUTATION);
            return sharedResult + "-" + actionResult + "-interactionTransformed";
        };
        builder.withInteractionComputation(TestNamespace.INTERACTION_COMPUTATION, interactionComputation, false)
                .withInteractionComputation(TestNamespace.INTERACTION_FEATURE, memoized -> memoized.compute(TestNamespace.INTERACTION_COMPUTATION))
                .withFeature(TestNamespace.INTERACTION_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        // Use transform
        List<TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(2, transformedList.size());

        // Non-memoized check
        // Shared: once for shared + once per action => total 3 first time
        assertEquals(3, sharedCounter.get());
        // Action: For each action => action comp once + interaction comp => 2 actions * 2 times =4
        assertEquals(4, actionCounter.get());
        assertEquals(2, interactionCounter.get());

        // Another transform call => increments again
        List<TransformedAction<TestAction>> transformedList2 = transformer.transform(computingRankingRequest);
        assertEquals(2, transformedList2.size());

        // Called again => shared comp 2 more times => total 5
        assertEquals(5, sharedCounter.get());
        // Action comp 2 more times => total 6 => plus interaction comp => total 8
        assertEquals(8, actionCounter.get());
        // interaction comp => 2 more => total 4
        assertEquals(4, interactionCounter.get());
    }

    @Test
    void testBulkScorer() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        TestAction action2 = new TestAction();
        action2.actionField = "actionData2";
        List<TestAction> actions = Arrays.asList(action1, action2);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        ComputingBulkScorer<TestShared, TestAction> bulkScorer = new ComputingBulkScorer<TestShared, TestAction>() {
            @Override
            public BulkScoreResponse<TestAction> score(ComputingRankingRequest<TestShared, TestAction> rankingRequest) {
                List<ScoringDecision<TestAction>> ret = new ArrayList<>();
                for (TestAction action : rankingRequest.rankingRequest().availableActions()) {
                    ret.add(ScoringDecision.of(action, 42.0));
                }
                return BulkScoreResponse.of(ret, com.hotvect.api.data.FeatureStoreResponseContainer.empty());
            }

            @Override
            public BulkScoreResponse<TestAction> score(RankingRequest<TestShared, TestAction> rankingRequest) {
                List<ScoringDecision<TestAction>> ret = new ArrayList<>();
                for (TestAction action : rankingRequest.availableActions()) {
                    ret.add(ScoringDecision.of(action, 42.0));
                }
                return BulkScoreResponse.of(ret, com.hotvect.api.data.FeatureStoreResponseContainer.empty());
            }
        };

        builder.withBulkScorer(TestNamespace.BULK_SCORER_FEATURE, bulkScorer)
                .withFeature(TestNamespace.BULK_SCORER_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);

        assertEquals(2, transformedList.size());
        for (TransformedAction<TestAction> transformed : transformedList) {
            assertTrue(transformed.transformed().asMap().containsKey(TestNamespace.BULK_SCORER_FEATURE));
            double score = (Double) transformed.transformed().get(TestNamespace.BULK_SCORER_FEATURE);
            assertEquals(42.0, score);
        }
    }

    @Test
    void testMissingComputationThrowsException() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();
        builder.withFeature(TestNamespace.SHARED_FEATURE.getName());
        NamespaceNotFoundException exception = assertThrows(NamespaceNotFoundException.class, builder::build);
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains(
                "Namespace: " + TestNamespace.SHARED_FEATURE.getName() + " is not registered"
        ));
    }
}
