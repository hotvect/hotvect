package com.hotvect.api.transformation;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ComputingCandidateTest {
    enum TestNamespace implements Namespace {
        SHARED_NS,
        ACTION_NS,
        INTERACTION_MEMO,
        INTERACTION_ONDEMAND,
        UNKNOWN,
        SHARED_COMPUTATION,
        ACTION_COMPUTATION,
        INTERACTION_COMPUTATION,
        UNKNOWN_COMPUTATION,
        COMPUTATION1,
        COMPUTATION2,
        COMPUTATION_ONE,
        KNOWN_COMPUTATION,
        COMUTATION1;

        @Override
        public String getName() {
            return this.name();
        }

        @Override
        public String toString() {
            return this.name();
        }
    }

    @Test
    void testBuildBasicCandidate() {
        Computing<String> shared = Computing.builder("shared").build();
        Computing<String> action = Computing.builder("action").build();

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                null,
                null,
                NamespacedRecord.empty()
        );

        assertNotNull(candidate.getShared());
        assertSame(shared, candidate.getShared());
        assertSame(action, candidate.getAction());
    }

    @Test
    void testComputeShared() {
        AtomicInteger counter = new AtomicInteger(0);

        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.SHARED_NS, RankingFeatureComputationDependency.SHARED);

        NamespacedRecord<Namespace, Computation<String, Object>> sharedMemo = new NamespacedRecordImpl<>();
        sharedMemo.put(TestNamespace.SHARED_NS, c -> {
            counter.incrementAndGet();
            return "sharedResult";
        });

        Mapping<Namespace, Computation<String, Object>> sharedMemoMapping =
                new Mapping<>(sharedMemo.asMap(), Namespace[]::new, Computation[]::new);

        Computing<String> shared = Computing.builder("shared")
                .withMemoizedComputations(sharedMemoMapping)
                .build();

        Computing<String> action = Computing.builder("action").build();

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                dependencyLookupMap,
                null,
                NamespacedRecord.empty()
        );

        String result = candidate.compute(TestNamespace.SHARED_NS);
        assertEquals("sharedResult", result);
        assertEquals(1, counter.get());

        // Memoized, no increment on second call
        result = candidate.compute(TestNamespace.SHARED_NS);
        assertEquals("sharedResult", result);
        assertEquals(1, counter.get());
    }

    @Test
    void testComputeAction() {
        AtomicInteger counter = new AtomicInteger(0);

        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.ACTION_NS, RankingFeatureComputationDependency.ACTION);

        Computing<String> shared = Computing.builder("shared").build();

        NamespacedRecord<Namespace, Computation<String, Object>> actionMemo = new NamespacedRecordImpl<>();
        actionMemo.put(TestNamespace.ACTION_NS, c -> {
            counter.incrementAndGet();
            return "actionResult";
        });

        Mapping<Namespace, Computation<String, Object>> actionMemoMapping =
                new Mapping<>(actionMemo.asMap(), Namespace[]::new, Computation[]::new);

        Computing<String> action = Computing.builder("action")
                .withMemoizedComputations(actionMemoMapping)
                .build();

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                dependencyLookupMap,
                null,
                NamespacedRecord.empty()
        );

        String result = candidate.compute(TestNamespace.ACTION_NS);
        assertEquals("actionResult", result);
        assertEquals(1, counter.get());

        // Again, memoized
        result = candidate.compute(TestNamespace.ACTION_NS);
        assertEquals("actionResult", result);
        assertEquals(1, counter.get());
    }

    @Test
    void testComputeInteractionMemoized() {
        AtomicInteger interactionCounter = new AtomicInteger(0);

        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_MEMO, RankingFeatureComputationDependency.INTERACTION);

        Computing<String> shared = Computing.builder("shared").build();
        Computing<String> action = Computing.builder("action").build();

        InteractingComputation<String, String, Object> memoizedInteraction = comp -> {
            interactionCounter.incrementAndGet();
            return "memoizedInteractionResult";
        };
        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> memoizedInteractions =
                new NamespacedRecordImpl<>(
                        new Namespace[]{TestNamespace.INTERACTION_MEMO},
                        new InteractingComputation[]{memoizedInteraction}
                );

        Mapping<Namespace, InteractingComputation<String, String, Object>> memoizedMapping =
                new Mapping<>(memoizedInteractions.asMap(), Namespace[]::new, InteractingComputation[]::new);

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                dependencyLookupMap,
                memoizedMapping,
                NamespacedRecord.empty()
        );

        String result = candidate.compute(TestNamespace.INTERACTION_MEMO);
        assertEquals("memoizedInteractionResult", result);
        assertEquals(1, interactionCounter.get());

        // Memoized, no increment second time
        result = candidate.compute(TestNamespace.INTERACTION_MEMO);
        assertEquals("memoizedInteractionResult", result);
        assertEquals(1, interactionCounter.get());
    }

    @Test
    void testComputeInteractionOnDemand() {
        AtomicInteger interactionCounter = new AtomicInteger(0);

        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_ONDEMAND, RankingFeatureComputationDependency.INTERACTION);

        Computing<String> shared = Computing.builder("shared").build();
        Computing<String> action = Computing.builder("action").build();

        InteractingComputation<String, String, Object> onDemandInteraction = comp -> {
            interactionCounter.incrementAndGet();
            return "onDemandInteractionResult";
        };

        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> onDemandInteractions =
                new NamespacedRecordImpl<>();
        onDemandInteractions.put(TestNamespace.INTERACTION_ONDEMAND, onDemandInteraction);

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                dependencyLookupMap,
                null,
                onDemandInteractions
        );

        String result = candidate.compute(TestNamespace.INTERACTION_ONDEMAND);
        assertEquals("onDemandInteractionResult", result);
        assertEquals(1, interactionCounter.get());

        // On-demand increments again
        result = candidate.compute(TestNamespace.INTERACTION_ONDEMAND);
        assertEquals("onDemandInteractionResult", result);
        assertEquals(2, interactionCounter.get());
    }

    @Test
    void testComputeUnknown() {
        Computing<String> shared = Computing.builder("shared").build();
        Computing<String> action = Computing.builder("action").build();

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                null,
                null,
                NamespacedRecord.empty()
        );

        WrongTransformationDefinitionException ex = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> candidate.compute(TestNamespace.UNKNOWN)
        );
        assertTrue(ex.getMessage().contains("cannot be found"));
    }

    @Test
    void testDebugCompute() {
        AtomicInteger interactionCounter = new AtomicInteger(0);

        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_ONDEMAND, RankingFeatureComputationDependency.INTERACTION);

        Computing<String> shared = Computing.builder("shared").build();
        Computing<String> action = Computing.builder("action").build();

        InteractingComputation<String, String, Object> onDemandInteraction = comp -> {
            interactionCounter.incrementAndGet();
            return "debugInteractionResult";
        };

        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> onDemandInteractions =
                new NamespacedRecordImpl<>();
        onDemandInteractions.put(TestNamespace.INTERACTION_ONDEMAND, onDemandInteraction);

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                dependencyLookupMap,
                null,
                onDemandInteractions
        );

        String result = candidate.debugCompute(TestNamespace.INTERACTION_ONDEMAND.toString());
        assertEquals("debugInteractionResult", result);
        assertEquals(1, interactionCounter.get());

        // Non-memoized, increments again
        result = candidate.debugCompute(TestNamespace.INTERACTION_ONDEMAND.toString());
        assertEquals("debugInteractionResult", result);
        assertEquals(2, interactionCounter.get());
    }

    @Test
    void testDebugComputeUnknown() {
        Computing<String> shared = Computing.builder("shared").build();
        Computing<String> action = Computing.builder("action").build();

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                null,
                null,
                NamespacedRecord.empty()
        );

        WrongTransformationDefinitionException ex = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> candidate.debugCompute(TestNamespace.UNKNOWN.toString())
        );
        assertTrue(ex.getMessage().contains("cannot be found"));
        assertTrue(ex.getMessage().contains("Did you mean"));
    }

    @Test
    void testDebugComputeShared() {
        AtomicInteger counter = new AtomicInteger(0);

        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.SHARED_NS, RankingFeatureComputationDependency.SHARED);

        NamespacedRecord<Namespace, Computation<String, Object>> sharedMemo = new NamespacedRecordImpl<>();
        sharedMemo.put(TestNamespace.SHARED_NS, c -> {
            counter.incrementAndGet();
            return "sharedDebugResult";
        });

        Mapping<Namespace, Computation<String, Object>> sharedMemoMapping =
                new Mapping<>(sharedMemo.asMap(), Namespace[]::new, Computation[]::new);

        Computing<String> shared = Computing.builder("shared")
                .withMemoizedComputations(sharedMemoMapping)
                .build();

        Computing<String> action = Computing.builder("action").build();

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                dependencyLookupMap,
                null,
                NamespacedRecord.empty()
        );

        String result = candidate.debugCompute(TestNamespace.SHARED_NS.toString());
        assertEquals("sharedDebugResult", result);
        assertEquals(1, counter.get());

        // Second call should be memoized
        result = candidate.debugCompute(TestNamespace.SHARED_NS.toString());
        assertEquals("sharedDebugResult", result);
        assertEquals(1, counter.get());
    }

    @Test
    void testAppendBothMemoizedAndOnDemandInteractions() {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_MEMO, RankingFeatureComputationDependency.INTERACTION);
        dependencyLookupMap.put(TestNamespace.INTERACTION_ONDEMAND, RankingFeatureComputationDependency.INTERACTION);

        Computing<String> shared = Computing.builder("shared").build();
        Computing<String> action = Computing.builder("action").build();

        ComputingCandidate<String, String> candidate = new ComputingCandidate<>(
                shared,
                action,
                dependencyLookupMap,
                null,
                NamespacedRecord.empty()
        );

        AtomicInteger memoizedCounter = new AtomicInteger(0);
        AtomicInteger onDemandCounter = new AtomicInteger(0);

        InteractingComputation<String, String, Object> memoizedInteraction = comp -> {
            memoizedCounter.incrementAndGet();
            return "appendMemoizedResult";
        };
        InteractingComputation<String, String, Object> onDemandInteraction = comp -> {
            onDemandCounter.incrementAndGet();
            return "appendOnDemandResult";
        };

        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> appendedMemoized =
                new NamespacedRecordImpl<>(new Namespace[]{TestNamespace.INTERACTION_MEMO},
                        new InteractingComputation[]{memoizedInteraction});
        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> appendedOnDemand =
                new NamespacedRecordImpl<>();
        appendedOnDemand.put(TestNamespace.INTERACTION_ONDEMAND, onDemandInteraction);

        Mapping<Namespace, InteractingComputation<String, String, Object>> appendedMemoizedMapping =
                new Mapping<>(appendedMemoized.asMap(), Namespace[]::new, InteractingComputation[]::new);
        Mapping<Namespace, InteractingComputation<String, String, Object>> appendedOnDemandMapping =
                new Mapping<>(appendedOnDemand.asMap(), Namespace[]::new, InteractingComputation[]::new);

        // Updated call: only three parameters now (memoized, on-demand, and new dependency map)
        candidate.appendComputations(
                appendedMemoizedMapping,
                appendedOnDemandMapping,
                null
        );

        String memoResult = candidate.compute(TestNamespace.INTERACTION_MEMO);
        assertEquals("appendMemoizedResult", memoResult);
        assertEquals(1, memoizedCounter.get());
        // Memoized, no increment second time
        memoResult = candidate.compute(TestNamespace.INTERACTION_MEMO);
        assertEquals("appendMemoizedResult", memoResult);
        assertEquals(1, memoizedCounter.get());

        String onDemandResult = candidate.compute(TestNamespace.INTERACTION_ONDEMAND);
        assertEquals("appendOnDemandResult", onDemandResult);
        assertEquals(1, onDemandCounter.get());
        // On-demand increments again
        onDemandResult = candidate.compute(TestNamespace.INTERACTION_ONDEMAND);
        assertEquals("appendOnDemandResult", onDemandResult);
        assertEquals(2, onDemandCounter.get());
    }

    @Test
    void testWithNoProcessing() {
        Computing<String> computingShared = Computing.builder("shared").build();
        Computing<String> computingAction = Computing.builder("action").build();

        ComputingCandidate<String, String> computingCandidate = new ComputingCandidate<>(
                computingShared,
                computingAction,
                null,
                null,
                NamespacedRecord.empty()
        );

        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computingCandidate.compute(TestNamespace.INTERACTION_COMPUTATION)
        );
        assertTrue(exception.getMessage().contains("cannot be found"));
    }

    @Test
    void testComputeInteractionTransformation() {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.SHARED_COMPUTATION, RankingFeatureComputationDependency.SHARED);
        dependencyLookupMap.put(TestNamespace.ACTION_COMPUTATION, RankingFeatureComputationDependency.ACTION);
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);

        String sharedData = "sharedData";
        String actionData = "actionData";

        AtomicInteger sharedCounter = new AtomicInteger(0);
        Computation<String, Object> sharedTransformation = comp -> {
            sharedCounter.incrementAndGet();
            return "sharedResult";
        };

        NamespacedRecord<Namespace, Computation<String, Object>> sharedMemoizedTransformations =
                new NamespacedRecordImpl<>(
                        new Namespace[]{TestNamespace.SHARED_COMPUTATION},
                        new Computation[]{sharedTransformation}
                );
        Mapping<Namespace, Computation<String, Object>> sharedMemoMap =
                new Mapping<>(sharedMemoizedTransformations.asMap(), Namespace[]::new, Computation[]::new);

        Computing<String> computingShared = Computing.builder(sharedData)
                .withMemoizedComputations(sharedMemoMap)
                .build();

        AtomicInteger actionCounter = new AtomicInteger(0);
        Computation<String, Object> actionTransformation = comp -> {
            actionCounter.incrementAndGet();
            return "actionResult";
        };

        NamespacedRecord<Namespace, Computation<String, Object>> actionMemoizedTransformations =
                new NamespacedRecordImpl<>(
                        new Namespace[]{TestNamespace.ACTION_COMPUTATION},
                        new Computation[]{actionTransformation}
                );
        Mapping<Namespace, Computation<String, Object>> actionMemoMap =
                new Mapping<>(actionMemoizedTransformations.asMap(), Namespace[]::new, Computation[]::new);

        Computing<String> computingAction = Computing.builder(actionData)
                .withMemoizedComputations(actionMemoMap)
                .build();

        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            String sharedResult = interactionComp.getShared()
                    .compute(TestNamespace.SHARED_COMPUTATION);
            String actionResult = interactionComp.getAction()
                    .compute(TestNamespace.ACTION_COMPUTATION);
            return sharedResult + "-" + actionResult + "-interactionResult";
        };

        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> interactionMemoizedTransformations =
                new NamespacedRecordImpl<>(
                        new Namespace[]{TestNamespace.INTERACTION_COMPUTATION},
                        new InteractingComputation[]{interactionTransformation}
                );
        Mapping<Namespace, InteractingComputation<String, String, Object>> interactionMapping =
                new Mapping<>(interactionMemoizedTransformations.asMap(), Namespace[]::new, InteractingComputation[]::new);

        ComputingCandidate<String, String> computingCandidate = new ComputingCandidate<>(
                computingShared,
                computingAction,
                dependencyLookupMap,
                interactionMapping,
                NamespacedRecord.empty()
        );

        String interactionResult = computingCandidate.compute(TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("sharedResult-actionResult-interactionResult", interactionResult);

        assertEquals(1, sharedCounter.get());
        assertEquals(1, actionCounter.get());
        assertEquals(1, interactionCounter.get());

        // Calling again should use memoized values
        interactionResult = computingCandidate.compute(TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("sharedResult-actionResult-interactionResult", interactionResult);

        assertEquals(1, sharedCounter.get());
        assertEquals(1, actionCounter.get());
        assertEquals(1, interactionCounter.get());
    }

    @Test
    void testComputeWithNonMemoizedInteractionTransformation() {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);

        String sharedData = "sharedData";
        String actionData = "actionData";

        Computing<String> computingShared = Computing.builder(sharedData).build();
        Computing<String> computingAction = Computing.builder(actionData).build();

        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            return "interactionResult";
        };

        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> interactionNonMemoizedTransformations =
                new NamespacedRecordImpl<>();
        interactionNonMemoizedTransformations.put(TestNamespace.INTERACTION_COMPUTATION, interactionTransformation);

        ComputingCandidate<String, String> computingCandidate = new ComputingCandidate<>(
                computingShared,
                computingAction,
                dependencyLookupMap,
                null,
                interactionNonMemoizedTransformations
        );

        String interactionResult = computingCandidate.compute(TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        assertEquals(1, interactionCounter.get());

        // Calling again should recompute
        interactionResult = computingCandidate.compute(TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        assertEquals(2, interactionCounter.get());
    }

    @Test
    void testAppendComputations() {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);

        String sharedData = "sharedData";
        String actionData = "actionData";

        Computing<String> computingShared = Computing.builder(sharedData).build();
        Computing<String> computingAction = Computing.builder(actionData).build();

        ComputingCandidate<String, String> computingCandidate = new ComputingCandidate<>(
                computingShared,
                computingAction,
                dependencyLookupMap,
                null,
                NamespacedRecord.empty()
        );

        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            return "interactionResult";
        };

        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> interactionMemoizedTransformations =
                new NamespacedRecordImpl<>(
                        new Namespace[]{TestNamespace.INTERACTION_COMPUTATION},
                        new InteractingComputation[]{interactionTransformation}
                );
        Mapping<Namespace, InteractingComputation<String, String, Object>> interactionMemoMapping =
                new Mapping<>(interactionMemoizedTransformations.asMap(), Namespace[]::new, InteractingComputation[]::new);

        // Updated call
        computingCandidate.appendComputations(
                interactionMemoMapping,
                null,
                null
        );

        String interactionResult = computingCandidate.compute(TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        assertEquals(1, interactionCounter.get());

        // Should be memoized now
        interactionResult = computingCandidate.compute(TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        assertEquals(1, interactionCounter.get());
    }

    @Test
    void testComputeUnknownComputation() {
        String sharedData = "sharedData";
        String actionData = "actionData";

        Computing<String> computingShared = Computing.builder(sharedData).build();
        Computing<String> computingAction = Computing.builder(actionData).build();

        ComputingCandidate<String, String> computingCandidate = new ComputingCandidate<>(
                computingShared,
                computingAction,
                null,
                null,
                NamespacedRecord.empty()
        );

        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computingCandidate.compute(TestNamespace.UNKNOWN_COMPUTATION)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("UNKNOWN_COMPUTATION"));
        assertTrue(message.contains("Did you mean"));
    }

    @Test
    void testDebugComputeSecondScenario() {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap =
                new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);

        String sharedData = "sharedData";
        String actionData = "actionData";

        Computing<String> computingShared = Computing.builder(sharedData).build();
        Computing<String> computingAction = Computing.builder(actionData).build();

        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            return "interactionResult";
        };

        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> interactionNonMemoizedTransformations =
                new NamespacedRecordImpl<>();
        interactionNonMemoizedTransformations.put(TestNamespace.INTERACTION_COMPUTATION, interactionTransformation);

        ComputingCandidate<String, String> computingCandidate = new ComputingCandidate<>(
                computingShared,
                computingAction,
                dependencyLookupMap,
                null,
                interactionNonMemoizedTransformations
        );

        String result = computingCandidate.debugCompute(TestNamespace.INTERACTION_COMPUTATION.toString());
        assertEquals("interactionResult", result);
        assertEquals(1, interactionCounter.get());

        // Calling again should recompute
        result = computingCandidate.debugCompute(TestNamespace.INTERACTION_COMPUTATION.toString());
        assertEquals("interactionResult", result);
        assertEquals(2, interactionCounter.get());
    }

    @Test
    void testSuggestAndThrowMechanism() {
        String sharedData = "sharedData";
        String actionData = "actionData";
        String typoComputationId = "COMPUTATOON1";

        Computing<String> computingShared = Computing.builder(sharedData).build();
        Computing<String> computingAction = Computing.builder(actionData).build();

        ComputingCandidate<String, String> computingCandidate = new ComputingCandidate<>(
                computingShared,
                computingAction,
                null,
                null,
                NamespacedRecord.empty()
        );

        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computingCandidate.debugCompute(typoComputationId)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("Did you mean"));
        assertTrue(message.contains("The requested computation COMPUTATOON1 cannot be found"));
    }
}
