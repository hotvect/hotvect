package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StandardRankingTransformerEagerTest {
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
        EAGER_FEATURE,
        EAGER_FEATURE_2;

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
    void testEagerTransformationWithNullEagerTransformation() {
        // Test that null eager transformation behaves as before
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        builder.withSharedComputation(TestNamespace.SHARED_FEATURE,
                request -> request.getOriginalInput().shared().sharedField + "-transformed", true)
               .withFeature(TestNamespace.SHARED_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(1, transformedList.size());

        TransformedAction<TestAction> transformed = transformedList.get(0);
        assertEquals("sharedData-transformed", transformed.transformed().get(TestNamespace.SHARED_FEATURE));
    }

    @Test
    void testEagerTransformationBasic() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        TestAction action2 = new TestAction();
        action2.actionField = "actionData2";
        List<TestAction> actions = Arrays.asList(action1, action2);

        AtomicInteger eagerCallCount = new AtomicInteger(0);
        EagerRankingTransformation<TestShared, TestAction> eagerTransformation = rankingRequest -> {
            eagerCallCount.incrementAndGet();
            Map<Namespace, Object> result = new HashMap<>();
            result.put(TestNamespace.EAGER_FEATURE, "eager-" + rankingRequest.shared().sharedField);
            result.put(TestNamespace.EAGER_FEATURE_2, "eager2-" + rankingRequest.availableActions().size());
            return result;
        };

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        // Register the namespaces in the dictionary first by adding dummy computations
        builder.withSharedPrecomputation(TestNamespace.EAGER_FEATURE, "placeholder")
               .withSharedPrecomputation(TestNamespace.EAGER_FEATURE_2, "placeholder")
               .withEagerTransformation(eagerTransformation)
               .withFeature(TestNamespace.EAGER_FEATURE.getName())
               .withFeature(TestNamespace.EAGER_FEATURE_2.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(2, transformedList.size());

        // Check that eager transformation was called exactly once
        assertEquals(1, eagerCallCount.get());

        // Check that both actions have the eager features
        for (TransformedAction<TestAction> transformed : transformedList) {
            NamespacedRecord<Namespace, Object> record = transformed.transformed();
            assertEquals("eager-sharedData", record.get(TestNamespace.EAGER_FEATURE));
            assertEquals("eager2-2", record.get(TestNamespace.EAGER_FEATURE_2));
            assertTrue(transformed.additionalProperties().isEmpty());
        }

        // Transform again - eager transformation should be called again (since it's per prepare() call)
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest2 = transformer.prepare(rankingRequest);
        List<TransformedAction<TestAction>> transformedList2 = transformer.transform(computingRankingRequest2);
        assertEquals(2, eagerCallCount.get()); // Called again during second prepare()
    }

    @Test
    void testEagerTransformationWithRegularComputations() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        AtomicInteger regularCallCount = new AtomicInteger(0);
        EagerRankingTransformation<TestShared, TestAction> eagerTransformation = rankingRequest -> {
            Map<Namespace, Object> result = new HashMap<>();
            result.put(TestNamespace.EAGER_FEATURE, "eager-" + rankingRequest.shared().sharedField);
            return result;
        };

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        builder.withSharedPrecomputation(TestNamespace.EAGER_FEATURE, "placeholder")
               .withEagerTransformation(eagerTransformation)
               .withFeature(TestNamespace.EAGER_FEATURE.getName())
               .withSharedComputation(TestNamespace.SHARED_FEATURE, request -> {
                   regularCallCount.incrementAndGet();
                   return request.getOriginalInput().shared().sharedField + "-regular";
               }, true)
               .withFeature(TestNamespace.SHARED_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(1, transformedList.size());

        TransformedAction<TestAction> transformed = transformedList.get(0);
        NamespacedRecord<Namespace, Object> record = transformed.transformed();

        // Both eager and regular features should be present
        assertEquals("eager-sharedData", record.get(TestNamespace.EAGER_FEATURE));
        assertEquals("sharedData-regular", record.get(TestNamespace.SHARED_FEATURE));
        assertEquals(1, regularCallCount.get());
    }

    @Test
    void testEagerTransformationWithPrecomputedValues() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        EagerRankingTransformation<TestShared, TestAction> eagerTransformation = rankingRequest -> {
            Map<Namespace, Object> result = new HashMap<>();
            result.put(TestNamespace.EAGER_FEATURE, "eager-value");
            // Test overriding a precomputed value
            result.put(TestNamespace.SHARED_FEATURE, "eager-override");
            return result;
        };

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        builder.withSharedPrecomputation(TestNamespace.EAGER_FEATURE, "placeholder")
               .withEagerTransformation(eagerTransformation)
               .withSharedPrecomputation(TestNamespace.SHARED_FEATURE, "precomputed-value")
               .withFeature(TestNamespace.EAGER_FEATURE.getName())
               .withFeature(TestNamespace.SHARED_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        List<TransformedAction<TestAction>> transformedList = transformer.transform(computingRankingRequest);
        assertEquals(1, transformedList.size());

        TransformedAction<TestAction> transformed = transformedList.get(0);
        NamespacedRecord<Namespace, Object> record = transformed.transformed();

        assertEquals("eager-value", record.get(TestNamespace.EAGER_FEATURE));
        // Eager transformation should override precomputed value
        assertEquals("eager-override", record.get(TestNamespace.SHARED_FEATURE));
    }

    @Test
    void testEagerTransformationNotSupportedInDependencyPrepare() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        EagerRankingTransformation<TestShared, TestAction> eagerTransformation = rankingRequest -> {
            Map<Namespace, Object> result = new HashMap<>();
            result.put(TestNamespace.EAGER_FEATURE, "eager-value");
            return result;
        };

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        builder.withSharedPrecomputation(TestNamespace.EAGER_FEATURE, "placeholder")
               .withEagerTransformation(eagerTransformation)
               .withFeature(TestNamespace.EAGER_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> firstPrepare = transformer.prepare(rankingRequest);

        // This should throw UnsupportedOperationException
        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> transformer.prepare(firstPrepare)
        );

        assertTrue(exception.getMessage().contains("Eager transformation within a dependency algorithm is not implemented yet"));
    }


}