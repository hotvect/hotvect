package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.*;
import com.hotvect.utils.Result;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StandardRankingTransformerAdvancedTest {
    static class TestShared {
        String sharedField;
    }

    static class TestAction {
        String actionField;
    }

    enum TestNamespace implements Namespace {
        ADVANCED_SHARED_COMPUTATION,
        ADVANCED_ACTION_COMPUTATION,
        ADVANCED_INTERACTION_COMPUTATION,
        ADVANCED_SHARED_FEATURE,
        ADVANCED_ACTION_FEATURE,
        ADVANCED_INTERACTION_FEATURE,
        ADVANCED_BULK_SCORER_FEATURE,
        ADVANCED_UNKNOWN_COMPUTATION;

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
    }

    @Test
    void testComputationSpecConfiguration() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        AtomicInteger sharedCounter = new AtomicInteger(0);
        Computation<RankingRequest<TestShared, TestAction>, Object> sharedComputation = memoized -> {
            sharedCounter.incrementAndGet();
            return "shared";
        };

        AtomicInteger actionCounter = new AtomicInteger(0);
        Computation<TestAction, Object> actionComputation = memoized -> {
            actionCounter.incrementAndGet();
            return "action";
        };

        builder.withSharedComputation(TestNamespace.ADVANCED_SHARED_COMPUTATION, sharedComputation)
                .withActionComputation(TestNamespace.ADVANCED_ACTION_COMPUTATION, actionComputation)
                .withFeature(TestNamespace.ADVANCED_SHARED_COMPUTATION.getName())
                .withFeature(TestNamespace.ADVANCED_ACTION_COMPUTATION.getName())
                .setComputationSpec(TestNamespace.ADVANCED_SHARED_COMPUTATION, ComputationSpec.LAZY_MEMOIZED)
                .setComputationSpec(TestNamespace.ADVANCED_ACTION_COMPUTATION, ComputationSpec.LAZY_ON_DEMAND);

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        TestShared shared = new TestShared();
        TestAction action1 = new TestAction();
        TestAction action2 = new TestAction();
        List<TestAction> actions = Arrays.asList(action1, action2);

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("id", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        // First transform
        List<TransformedAction<TestAction>> result1 = transformer.transform(computingRankingRequest);
        assertEquals(2, result1.size());
        assertEquals(1, sharedCounter.get()); // Memoized
        assertEquals(2, actionCounter.get()); // Called for each action

        // Second transform
        List<TransformedAction<TestAction>> result2 = transformer.transform(computingRankingRequest);
        assertEquals(2, result2.size());
        assertEquals(1, sharedCounter.get()); // Still memoized
        assertEquals(4, actionCounter.get()); // Called again (not memoized)
    }

    @Test
    void testExceptionHandlingInComputation() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        Computation<RankingRequest<TestShared, TestAction>, Object> failingComputation = memoized -> {
            throw new RuntimeException("Computation failed");
        };

        builder.withSharedComputation(TestNamespace.ADVANCED_SHARED_COMPUTATION, failingComputation)
                .withFeature(TestNamespace.ADVANCED_SHARED_COMPUTATION.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        TestShared shared = new TestShared();
        TestAction action = new TestAction();
        List<TestAction> actions = Collections.singletonList(action);

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("id", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        assertThrows(RuntimeException.class, () -> transformer.transform(computingRankingRequest));
    }

    @Test
    void testResultTypeComputation() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        Computation<RankingRequest<TestShared, TestAction>, Result<String, String>> resultComputation = 
            memoized -> new Result.Success<>("success");

        builder.withSharedComputation(TestNamespace.ADVANCED_SHARED_COMPUTATION, resultComputation)
                .withFeature(TestNamespace.ADVANCED_SHARED_COMPUTATION.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        TestShared shared = new TestShared();
        TestAction action = new TestAction();
        List<TestAction> actions = Collections.singletonList(action);

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("id", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> result = transformer.transform(computingRankingRequest);
        assertEquals(1, result.size());
        
        NamespacedRecord<Namespace, Object> record = result.get(0).transformed();
        assertTrue(record.asMap().containsKey(TestNamespace.ADVANCED_SHARED_COMPUTATION));
        
        @SuppressWarnings("unchecked")
        Result<String, String> computationResult = (Result<String, String>) record.get(TestNamespace.ADVANCED_SHARED_COMPUTATION);
        assertTrue(computationResult instanceof Result.Success);
        if (computationResult instanceof Result.Success<String, String> success) {
            assertEquals("success", success.value());
        }
    }

    @Test
    void testMultipleFeatureNamespaces() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        // Add multiple computations and features
        builder.withSharedComputation(TestNamespace.ADVANCED_SHARED_COMPUTATION, memoized -> "shared1")
                .withSharedComputation(TestNamespace.ADVANCED_SHARED_FEATURE, memoized -> "shared2")
                .withActionComputation(TestNamespace.ADVANCED_ACTION_COMPUTATION, memoized -> "action1")
                .withActionComputation(TestNamespace.ADVANCED_ACTION_FEATURE, memoized -> "action2")
                .withInteractionComputation(TestNamespace.ADVANCED_INTERACTION_COMPUTATION, memoized -> "interaction1")
                .withInteractionComputation(TestNamespace.ADVANCED_INTERACTION_FEATURE, memoized -> "interaction2")
                .withFeature(TestNamespace.ADVANCED_SHARED_COMPUTATION.getName())
                .withFeature(TestNamespace.ADVANCED_SHARED_FEATURE.getName())
                .withFeature(TestNamespace.ADVANCED_ACTION_COMPUTATION.getName())
                .withFeature(TestNamespace.ADVANCED_ACTION_FEATURE.getName())
                .withFeature(TestNamespace.ADVANCED_INTERACTION_COMPUTATION.getName())
                .withFeature(TestNamespace.ADVANCED_INTERACTION_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        TestShared shared = new TestShared();
        TestAction action = new TestAction();
        List<TestAction> actions = Collections.singletonList(action);

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("id", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> result = transformer.transform(computingRankingRequest);
        assertEquals(1, result.size());

        NamespacedRecord<Namespace, Object> record = result.get(0).transformed();
        assertEquals(6, record.asMap().size());
        assertEquals("shared1", record.get(TestNamespace.ADVANCED_SHARED_COMPUTATION));
        assertEquals("shared2", record.get(TestNamespace.ADVANCED_SHARED_FEATURE));
        assertEquals("action1", record.get(TestNamespace.ADVANCED_ACTION_COMPUTATION));
        assertEquals("action2", record.get(TestNamespace.ADVANCED_ACTION_FEATURE));
        assertEquals("interaction1", record.get(TestNamespace.ADVANCED_INTERACTION_COMPUTATION));
        assertEquals("interaction2", record.get(TestNamespace.ADVANCED_INTERACTION_FEATURE));
    }

    @Test
    void testEmptyActionsListHandling() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        builder.withSharedComputation(TestNamespace.ADVANCED_SHARED_COMPUTATION, memoized -> "shared")
                .withFeature(TestNamespace.ADVANCED_SHARED_COMPUTATION.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        TestShared shared = new TestShared();
        List<TestAction> actions = Collections.emptyList();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("id", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> result = transformer.transform(computingRankingRequest);
        assertTrue(result.isEmpty());
    }

    @Test
    void testChainedComputations() {
        StandardRankingTransformer.Builder<TestShared, TestAction> builder =
                StandardRankingTransformer.builder();

        // Create a chain of computations that depend on each other
        builder.withSharedComputation(TestNamespace.ADVANCED_SHARED_COMPUTATION, 
                memoized -> memoized.getOriginalInput().shared().sharedField)
                .withSharedComputation(TestNamespace.ADVANCED_SHARED_FEATURE,
                        memoized -> memoized.compute(TestNamespace.ADVANCED_SHARED_COMPUTATION) + "-processed")
                .withActionComputation(TestNamespace.ADVANCED_ACTION_COMPUTATION,
                        memoized -> memoized.getOriginalInput().actionField)
                .withActionComputation(TestNamespace.ADVANCED_ACTION_FEATURE,
                        memoized -> memoized.compute(TestNamespace.ADVANCED_ACTION_COMPUTATION) + "-processed")
                .withInteractionComputation(TestNamespace.ADVANCED_INTERACTION_FEATURE,
                        memoized -> {
                            String sharedValue = (String) memoized.getShared().compute(TestNamespace.ADVANCED_SHARED_FEATURE);
                            String actionValue = (String) memoized.getAction().compute(TestNamespace.ADVANCED_ACTION_FEATURE);
                            return sharedValue + ":" + actionValue;
                        })
                .withFeature(TestNamespace.ADVANCED_INTERACTION_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action = new TestAction();
        action.actionField = "actionData";
        List<TestAction> actions = Collections.singletonList(action);

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("id", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> result = transformer.transform(computingRankingRequest);
        assertEquals(1, result.size());

        NamespacedRecord<Namespace, Object> record = result.get(0).transformed();
        assertEquals("sharedData-processed:actionData-processed", 
                record.get(TestNamespace.ADVANCED_INTERACTION_FEATURE));
    }
}
