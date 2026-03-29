package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
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

    enum RawNamespace implements Namespace {
        EAGER_RAW,
        EAGER_RAW_2
    }

    enum EagerId implements Namespace {
        EAGER_STEP,
        EAGER_STEP_2
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
    void testEagerTransformationExposesFeatureStoreResponsesToComputable() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        Namespace viewNamespace = Namespaces.declareNamespace("customer_features_legacy");
        FeatureStoreResponse featureStoreResponse = SimpleFeatureStoreResponse.success(Map.of());

        EagerRankingTransformation<TestShared, TestAction> eagerTransformation = rankingRequest -> Map.of(
                viewNamespace, featureStoreResponse,
                RawNamespace.EAGER_RAW, "some-non-feature-store-value"
        );

        StandardRankingTransformer<TestShared, TestAction> transformer = StandardRankingTransformer
                .<TestShared, TestAction>builder()
                .withEagerTransformation(EagerId.EAGER_STEP, eagerTransformation)
                .build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> computingRankingRequest = transformer.prepare(rankingRequest);

        assertSame(
                featureStoreResponse,
                computingRankingRequest.shared().compute(viewNamespace)
        );
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
            return Map.of(
                    RawNamespace.EAGER_RAW, "eager-" + rankingRequest.shared().sharedField,
                    RawNamespace.EAGER_RAW_2, "eager2-" + rankingRequest.availableActions().size()
            );
        };

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        builder
                .withEagerTransformation(EagerId.EAGER_STEP, eagerTransformation)
                .withSharedComputation(TestNamespace.EAGER_FEATURE, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW), true)
                .withSharedComputation(TestNamespace.EAGER_FEATURE_2, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW_2), true)
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
        EagerRankingTransformation<TestShared, TestAction> eagerTransformation = rankingRequest ->
                Map.of(RawNamespace.EAGER_RAW, "eager-" + rankingRequest.shared().sharedField);

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();
        
        builder.withEagerTransformation(EagerId.EAGER_STEP, eagerTransformation)
               .withSharedComputation(TestNamespace.EAGER_FEATURE, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW), true)
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

        EagerRankingTransformation<TestShared, TestAction> eagerTransformation =
                _request -> Map.of(RawNamespace.EAGER_RAW, "eager-value");

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        builder.withSharedPrecomputation(RawNamespace.EAGER_RAW, "precomputed-value")
                .withEagerTransformation(EagerId.EAGER_STEP, eagerTransformation)
                .withSharedComputation(TestNamespace.EAGER_FEATURE, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW), true)
                .withFeature(TestNamespace.EAGER_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        assertThrows(IllegalStateException.class, () -> transformer.prepare(rankingRequest));
    }

    @Test
    void testEagerTransformationSupportedInDependencyPrepare() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        AtomicInteger eagerCallCount = new AtomicInteger(0);
        EagerRankingTransformation<TestShared, TestAction> eagerTransformation = _request -> {
            eagerCallCount.incrementAndGet();
            return Map.of(RawNamespace.EAGER_RAW, "eager-value");
        };

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();

        builder.withEagerTransformation(EagerId.EAGER_STEP, eagerTransformation)
                .withSharedComputation(TestNamespace.EAGER_FEATURE, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW), true)
                .withFeature(TestNamespace.EAGER_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> firstPrepare = transformer.prepare(rankingRequest);

        assertEquals(1, eagerCallCount.get());

        // This should not throw, and it should not re-run the eager transformation.
        ComputingRankingRequest<TestShared, TestAction> secondPrepare = transformer.prepare(firstPrepare);
        assertEquals(1, eagerCallCount.get());

        List<TransformedAction<TestAction>> transformed = transformer.transform(secondPrepare);
        assertEquals(1, transformed.size());
        assertEquals("eager-value", transformed.get(0).transformed().get(TestNamespace.EAGER_FEATURE));
    }

    @Test
    void testEagerTransformationRequiresNonNullId() {
        EagerRankingTransformation<TestShared, TestAction> eagerTransformation =
                _request -> Map.of(RawNamespace.EAGER_RAW, "eager-value");

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();
        assertThrows(NullPointerException.class, () -> builder.withEagerTransformation(null, eagerTransformation));
    }

    @Test
    void testEagerTransformationIdMustNotBeFeatureNamespace() {
        EagerRankingTransformation<TestShared, TestAction> eagerTransformation =
                _request -> Map.of(RawNamespace.EAGER_RAW, "eager-value");

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.withEagerTransformation(TestNamespace.EAGER_FEATURE, eagerTransformation));
    }

    @Test
    void testMultipleEagerTransformationsRunInInsertionOrderAndSkipInDependencyPrepare() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        List<EagerId> callOrder = new ArrayList<>();
        AtomicInteger eagerCalls = new AtomicInteger(0);

        EagerRankingTransformation<TestShared, TestAction> eager1 = _request -> {
            eagerCalls.incrementAndGet();
            callOrder.add(EagerId.EAGER_STEP);
            return Map.of(RawNamespace.EAGER_RAW, "eager-1");
        };
        EagerRankingTransformation<TestShared, TestAction> eager2 = _request -> {
            eagerCalls.incrementAndGet();
            callOrder.add(EagerId.EAGER_STEP_2);
            return Map.of(RawNamespace.EAGER_RAW_2, "eager-2");
        };

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();
        builder.withEagerTransformation(EagerId.EAGER_STEP, eager1)
                .withEagerTransformation(EagerId.EAGER_STEP_2, eager2)
                .withSharedComputation(TestNamespace.EAGER_FEATURE, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW), true)
                .withSharedComputation(TestNamespace.EAGER_FEATURE_2, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW_2), true)
                .withFeature(TestNamespace.EAGER_FEATURE.getName())
                .withFeature(TestNamespace.EAGER_FEATURE_2.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();

        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        ComputingRankingRequest<TestShared, TestAction> firstPrepare = transformer.prepare(rankingRequest);
        assertEquals(2, eagerCalls.get());
        assertEquals(List.of(EagerId.EAGER_STEP, EagerId.EAGER_STEP_2), callOrder);

        // Dependency-prepare should reuse eager work (by marker ids), so no new eager calls.
        ComputingRankingRequest<TestShared, TestAction> secondPrepare = transformer.prepare(firstPrepare);
        assertEquals(2, eagerCalls.get());
        assertEquals(List.of(EagerId.EAGER_STEP, EagerId.EAGER_STEP_2), callOrder);

        List<TransformedAction<TestAction>> transformed = transformer.transform(secondPrepare);
        assertEquals("eager-1", transformed.get(0).transformed().get(TestNamespace.EAGER_FEATURE));
        assertEquals("eager-2", transformed.get(0).transformed().get(TestNamespace.EAGER_FEATURE_2));
    }

    @Test
    void testEagerTransformationsCollisionBetweenStepsFails() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        EagerRankingTransformation<TestShared, TestAction> eager1 = _request -> Map.of(RawNamespace.EAGER_RAW, "eager-1");
        EagerRankingTransformation<TestShared, TestAction> eager2 = _request -> Map.of(RawNamespace.EAGER_RAW, "eager-2");

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();
        builder.withEagerTransformation(EagerId.EAGER_STEP, eager1)
                .withEagerTransformation(EagerId.EAGER_STEP_2, eager2)
                .withSharedComputation(TestNamespace.EAGER_FEATURE, sharedCompute -> sharedCompute.compute(RawNamespace.EAGER_RAW), true)
                .withFeature(TestNamespace.EAGER_FEATURE.getName());

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        assertThrows(IllegalStateException.class, () -> transformer.prepare(rankingRequest));
    }

    @Test
    void testEagerTransformationCannotWriteItsOwnIdNamespace() {
        TestShared shared = new TestShared();
        shared.sharedField = "sharedData";
        TestAction action1 = new TestAction();
        action1.actionField = "actionData1";
        List<TestAction> actions = List.of(action1);

        EagerRankingTransformation<TestShared, TestAction> eagerTransformation =
                _request -> Map.of(EagerId.EAGER_STEP, "not-allowed");

        StandardRankingTransformer.Builder<TestShared, TestAction> builder = StandardRankingTransformer.builder();
        builder.withEagerTransformation(EagerId.EAGER_STEP, eagerTransformation);

        StandardRankingTransformer<TestShared, TestAction> transformer = builder.build();
        RankingRequest<TestShared, TestAction> rankingRequest = new RankingRequest<>("exampleId", shared, actions);
        assertThrows(IllegalStateException.class, () -> transformer.prepare(rankingRequest));
    }

}
