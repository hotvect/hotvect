package com.hotvect.api.transformation.computation;

import com.google.common.collect.Maps;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.transformation.Computation;
import com.hotvect.api.transformation.Computing;
import com.hotvect.api.transformation.Holder;
import com.hotvect.api.transformation.WrongTransformationDefinitionException;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ComputingTest {
    enum TestNamespace implements Namespace {
        COMPUTATION1,
        COMPUTATION2,
        COMPUTATION_ONE,
        KNOWN_COMPUTATION,
        UNKNOWN_COMPUTATION,
        COMUTATION1, // Typo of COMPUTATION1
        INTERACTION_COMPUTATION;

        @Override
        public String getName() {
            return name();
        }
    }

    @Test
    void testWithArgument() {
        String argument = "testArgument";
        Computing<String> computing = Computing.builder(argument)
                .build();
        assertNotNull(computing);
        assertEquals(argument, computing.getOriginalInput());
    }

    @Test
    void testComputeWithNonMemoizedTransformation() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();

        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoized = new NamespacedRecordImpl<>();
        nonMemoized.put(computationId, transformation);

        Computing<String> computing = Computing.builder(argument)
                .withOnDemandComputations(nonMemoized)
                .build();

        Integer result = computing.compute(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());

        // Calling again should recompute since it's non-memoized
        result = computing.compute(computationId);
        assertEquals(2, result.intValue());
        assertEquals(2, computationCounter.get());
    }

    @Test
    void testComputeWithNonMemoizedTransformation_Appending() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();

        Mapping<Namespace, Computation<String, Object>> nonMemoized =
                new Mapping<>(Collections.singletonMap(computationId, transformation),
                        Namespace[]::new,
                        Computation[]::new);

        Computing<String> computing = Computing.builder(argument).build();
        computing.appendComputations(null, nonMemoized);

        Integer result = computing.compute(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());

        // Calling again should recompute
        result = computing.compute(computationId);
        assertEquals(2, result.intValue());
        assertEquals(2, computationCounter.get());
    }

    @Test
    void testComputeWithMemoizedTransformation() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();

        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(new Namespace[]{computationId}, new Computation[]{transformation});

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        Integer result = computing.compute(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
        // Calling again should return cached value
        result = computing.compute(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
    }

    @Test
    void testComputeWithMemoizedTransformation_Appending() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();

        Computing<String> computing = Computing.builder(argument).build();

        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(new Namespace[]{computationId}, new Computation[]{transformation});
        computing.appendComputations(memoizedTransformations, null);

        Integer result = computing.compute(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
        // Calling again should return cached value
        result = computing.compute(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
    }

    @Test
    void testAppendComputations() {
        String argument = "testArgument";
        Namespace computationId1 = TestNamespace.COMPUTATION1;
        Namespace computationId2 = TestNamespace.COMPUTATION2;
        AtomicInteger computationCounter1 = new AtomicInteger(0);
        AtomicInteger computationCounter2 = new AtomicInteger(0);
        AtomicInteger computationCounter3 = new AtomicInteger(0);

        Computation<String, Object> transformation1 = comp -> computationCounter1.incrementAndGet();
        Computation<String, Object> transformation2 = comp -> computationCounter2.incrementAndGet();
        Computation<String, Object> nonComputation = comp -> computationCounter3.incrementAndGet();

        // Initially add memoized transformation1
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(Collections.singletonMap(computationId1, transformation1),
                        Namespace[]::new,
                        Computation[]::new);

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        // Compute the first computation
        Integer result1 = computing.compute(computationId1);
        assertEquals(1, result1.intValue());
        assertEquals(1, computationCounter1.get());

        // Append a new memoized transformation2
        Mapping<Namespace, Computation<String, Object>> newMemoizedTransformations =
                new Mapping<>(Collections.singletonMap(computationId2, transformation2),
                        Namespace[]::new,
                        Computation[]::new);
        computing.appendComputations(newMemoizedTransformations, null);

        // Compute the new computation
        Integer result2 = computing.compute(computationId2);
        assertEquals(1, result2.intValue());
        assertEquals(1, computationCounter2.get());

        // Ensure the first computation still returns cached value
        result1 = computing.compute(computationId1);
        assertEquals(1, result1.intValue());
        assertEquals(1, computationCounter1.get());

        // Append non-memoized transformation
        Mapping<Namespace, Computation<String, Object>> nonMemoized =
                new Mapping<>(Collections.singletonMap(TestNamespace.KNOWN_COMPUTATION, nonComputation),
                        Namespace[]::new,
                        Computation[]::new);
        computing.appendComputations(null, nonMemoized);

        // Compute non-memoized transformation
        Integer result3 = computing.compute(TestNamespace.KNOWN_COMPUTATION);
        assertEquals(1, result3.intValue());
        assertEquals(1, computationCounter3.get());

        // Calling again should recompute
        result3 = computing.compute(TestNamespace.KNOWN_COMPUTATION);
        assertEquals(2, result3.intValue());
        assertEquals(2, computationCounter3.get());
    }

    @Test
    void testComputeWithPrecalculated() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        Integer precalculatedValue = 36;

        Map<Namespace, Object> precalculated = new HashMap<>();
        precalculated.put(computationId, precalculatedValue);

        // Wrap in Holder
        NamespacedRecord<Namespace, Holder<Object>> precalculatedRecord =
                new NamespacedRecordImpl<>(
                        Maps.transformValues(precalculated, Holder::new)
                );

        Computing<String> computing = Computing.builder(argument)
                .withPrecalculated(precalculatedRecord)
                .build();

        Integer result = computing.compute(computationId);
        assertEquals(precalculatedValue, result);
    }

    @Test
    void testComputeWithPrecalculatedAndAppendTransformations() {
        String argument = "testArgument";
        Namespace precalculatedComputationId = TestNamespace.COMPUTATION1;
        Integer precalculatedValue = 36;

        Map<Namespace, Object> precalculated = new HashMap<>();
        precalculated.put(precalculatedComputationId, precalculatedValue);

        NamespacedRecord<Namespace, Holder<Object>> precalculatedRecord =
                new NamespacedRecordImpl<>(
                        Maps.transformValues(precalculated, Holder::new)
                );

        Computing<String> computing = Computing.builder(argument)
                .withPrecalculated(precalculatedRecord)
                .build();

        // Test that precalculated value is returned
        Integer result = computing.compute(precalculatedComputationId);
        assertEquals(precalculatedValue, result);

        // Append memoized transformation
        Namespace memoizedComputationId = TestNamespace.COMPUTATION2;
        AtomicInteger memoizedCounter = new AtomicInteger(0);
        Computation<String, Object> computation = comp -> memoizedCounter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(Collections.singletonMap(memoizedComputationId, computation),
                        Namespace[]::new,
                        Computation[]::new);
        computing.appendComputations(memoizedTransformations, null);

        // Compute memoized transformation
        Integer memoizedResult = computing.compute(memoizedComputationId);
        assertEquals(1, memoizedResult.intValue());
        assertEquals(1, memoizedCounter.get());

        // Calling again should return cached value
        memoizedResult = computing.compute(memoizedComputationId);
        assertEquals(1, memoizedResult.intValue());
        assertEquals(1, memoizedCounter.get());

        // Append non-memoized transformation
        Namespace nonMemoizedComputationId = TestNamespace.KNOWN_COMPUTATION;
        AtomicInteger nonMemoizedCounter = new AtomicInteger(0);
        Computation<String, Object> nonComputation = comp -> nonMemoizedCounter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> nonMemoizedTransformations =
                new Mapping<>(Collections.singletonMap(nonMemoizedComputationId, nonComputation),
                        Namespace[]::new,
                        Computation[]::new);
        computing.appendComputations(null, nonMemoizedTransformations);

        // Compute non-memoized transformation
        Integer nonMemoizedResult = computing.compute(nonMemoizedComputationId);
        assertEquals(1, nonMemoizedResult.intValue());
        assertEquals(1, nonMemoizedCounter.get());

        // Calling again should recompute
        nonMemoizedResult = computing.compute(nonMemoizedComputationId);
        assertEquals(2, nonMemoizedResult.intValue());
        assertEquals(2, nonMemoizedCounter.get());
    }

    @Test
    void testComputeThrowsExceptionForUnknownComputation() {
        String argument = "testArgument";
        Namespace knownComputationId = TestNamespace.KNOWN_COMPUTATION;
        Namespace unknownComputationId = TestNamespace.UNKNOWN_COMPUTATION;

        Computation<String, Object> transformation = comp -> 1;
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(Collections.singletonMap(knownComputationId, transformation),
                        Namespace[]::new,
                        Computation[]::new);

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computing.compute(unknownComputationId)
        );
        assertTrue(exception.getMessage().contains("cannot be found"));
    }

    @Test
    void testDebugCompute() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(new Namespace[]{computationId}, new Computation[]{transformation});

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        Integer result = computing.debugCompute(computationId.getName());
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());

        // Calling again should return cached value
        result = computing.debugCompute(computationId.getName());
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
    }

    @Test
    void testDebugComputeUnknownComputation() {
        String argument = "testArgument";
        Namespace knownComputationId = TestNamespace.KNOWN_COMPUTATION;
        Computation<String, Object> transformation = comp -> 42;
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(new Namespace[]{knownComputationId}, new Computation[]{transformation});

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        WrongTransformationDefinitionException ex = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computing.debugCompute(TestNamespace.UNKNOWN_COMPUTATION.getName())
        );
        assertTrue(ex.getMessage().contains("cannot be found"));
    }

    @Test
    void testDebugComputePrecalculated() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        int precalculatedValue = 999;
        Map<Namespace, Object> precalculated = new HashMap<>();
        precalculated.put(computationId, precalculatedValue);

        NamespacedRecord<Namespace, Holder<Object>> precalculatedRecord =
                new NamespacedRecordImpl<>(Maps.transformValues(precalculated, Holder::new));

        Computing<String> computing = Computing.builder(argument)
                .withPrecalculated(precalculatedRecord)
                .build();

        Integer result = computing.debugCompute(computationId.getName());
        assertEquals(precalculatedValue, result.intValue());
    }

    @Test
    void testMemoizationPerformance_Memoized() throws InterruptedException {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;

        // Define a computationally expensive transformation
        Computation<String, Object> expensiveTransformation = comp -> {
            String input = comp.getOriginalInput();
            String hash = input;
            for (int i = 0; i < 1000; i++) {
                hash = Integer.toString(hash.hashCode());
            }
            return hash;
        };

        // Non-memoized version
        Computing<String> nonComputing = Computing.builder(argument).build();
        Mapping<Namespace, Computation<String, Object>> nonMemoizedTransformations =
                new Mapping<>(
                        Collections.singletonMap(computationId, expensiveTransformation),
                        Namespace[]::new,
                        Computation[]::new
                );
        nonComputing.appendComputations(null, nonMemoizedTransformations);

        // Memoized version
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(
                        Collections.singletonMap(computationId, expensiveTransformation),
                        Namespace[]::new,
                        Computation[]::new
                );
        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        int numCalls = 1000;
        int numThreads = 10;

        ExecutorService executorNonMemoized = Executors.newFixedThreadPool(numThreads);
        List<Callable<Object>> nonMemoizedTasks = new ArrayList<>();
        for (int i = 0; i < numCalls; i++) {
            nonMemoizedTasks.add(() -> nonComputing.compute(computationId));
        }
        long startTimeNonMemoized = System.nanoTime();
        executorNonMemoized.invokeAll(nonMemoizedTasks);
        long durationNonMemoized = System.nanoTime() - startTimeNonMemoized;
        executorNonMemoized.shutdown();

        ExecutorService executorMemoized = Executors.newFixedThreadPool(numThreads);
        List<Callable<Object>> memoizedTasks = new ArrayList<>();
        for (int i = 0; i < numCalls; i++) {
            memoizedTasks.add(() -> computing.compute(computationId));
        }
        long startTimeMemoized = System.nanoTime();
        executorMemoized.invokeAll(memoizedTasks);
        long durationMemoized = System.nanoTime() - startTimeMemoized;
        executorMemoized.shutdown();

        // Just a sanity check that memoized is at least not drastically slower
        assertTrue(durationMemoized < durationNonMemoized * 2,
                "Memoized computation should generally be faster or at least not significantly slower");
    }

    @Test
    void testAppendComputations_DuplicateComputationId() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);

        Computation<String, Object> transformation1 = comp -> counter1.incrementAndGet();
        Computation<String, Object> transformation2 = comp -> counter2.incrementAndGet();

        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(new Namespace[]{computationId}, new Computation[]{transformation1});
        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        // Attempt to append a new transformation under the same computationId
        Mapping<Namespace, Computation<String, Object>> newMemoizedTransformations =
                new Mapping<>(new Namespace[]{computationId}, new Computation[]{transformation2});
        computing.appendComputations(newMemoizedTransformations, null);

        // Compute
        Integer result = computing.compute(computationId);

        // transformation1 should have been used (cached after first call)
        assertEquals(1, counter1.get());
        assertEquals(0, counter2.get());

        // Calling again uses cached value
        computing.compute(computationId);
        assertEquals(1, counter1.get());
        assertEquals(0, counter2.get());
    }

    @Test
    void testAppendNonMemoizedTransformations_DuplicateComputationId() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);

        Computation<String, Object> transformation1 = comp -> counter1.incrementAndGet();
        Computation<String, Object> transformation2 = comp -> counter2.incrementAndGet();

        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoizedTransformations =
                new NamespacedRecordImpl<>();
        nonMemoizedTransformations.put(computationId, transformation1);

        Computing<String> computing = Computing.builder(argument)
                .withOnDemandComputations(nonMemoizedTransformations)
                .build();

        // Attempt to append a new transformation for same computationId
        Mapping<Namespace, Computation<String, Object>> newNonMemoizedTransformations =
                new Mapping<>(Collections.singletonMap(computationId, transformation2),
                        Namespace[]::new,
                        Computation[]::new);
        computing.appendComputations(null, newNonMemoizedTransformations);

        // Call compute on computationId multiple times
        computing.compute(computationId);
        computing.compute(computationId);

        // transformation1 should have been used each time
        assertEquals(2, counter1.get());
        assertEquals(0, counter2.get());
    }

    @Test
    void testComputeWithPrecalculatedAndAppendDuplicate() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        Integer precalculatedValue = 36;
        AtomicInteger counter = new AtomicInteger(0);

        Map<Namespace, Object> precalculated = new HashMap<>();
        precalculated.put(computationId, precalculatedValue);

        NamespacedRecord<Namespace, Holder<Object>> precalculatedRecord =
                new NamespacedRecordImpl<>(Maps.transformValues(precalculated, Holder::new));

        Computing<String> computing = Computing.builder(argument)
                .withPrecalculated(precalculatedRecord)
                .build();

        // Try to append a transformation under the same ID
        Computation<String, Object> transformation = comp -> counter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(new Namespace[]{computationId}, new Computation[]{transformation});
        computing.appendComputations(memoizedTransformations, null);

        Integer result = computing.compute(computationId);
        // The precalculated value should be returned, transformation not called
        assertEquals(precalculatedValue, result);
        assertEquals(0, counter.get());
    }

    @Test
    void testSuggestAndThrowMechanism() {
        String argument = "testArgument";
        Namespace registeredComputationId = TestNamespace.COMPUTATION1;
        String typoComputationId = "COMPUTATOON1";
        Computation<String, Object> transformation = comp -> 1;

        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(Collections.singletonMap(registeredComputationId, transformation),
                        Namespace[]::new,
                        Computation[]::new);

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computing.debugCompute(typoComputationId)
        );
        assertTrue(exception.getMessage().contains("Did you mean"));
        assertTrue(exception.getMessage().contains(registeredComputationId.getName()));
    }

    @Test
    void testSuggestAndThrowMechanismWithCompute() {
        String argument = "testArgument";
        Namespace registeredComputationId = TestNamespace.COMPUTATION1;
        Namespace unregisteredComputationId = TestNamespace.COMUTATION1; // Typo of COMPUTATION1

        Computation<String, Object> transformation = comp -> 1;
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(new Namespace[]{registeredComputationId}, new Computation[]{transformation});

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .build();

        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computing.compute(unregisteredComputationId)
        );
        assertTrue(exception.getMessage().contains("cannot be found"));
        assertTrue(exception.getMessage().contains("Did you mean"));
        assertTrue(exception.getMessage().contains(registeredComputationId.getName()));
    }

    @Test
    void testCompute_WithSameComputationIdInMemoizedAndOnDemand() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger memoizedCounter = new AtomicInteger(0);
        AtomicInteger onDemandCounter = new AtomicInteger(0);

        Computation<String, Object> memoizedTransformation = comp -> memoizedCounter.incrementAndGet();
        Computation<String, Object> onDemandTransformation = comp -> onDemandCounter.incrementAndGet();

        Mapping<Namespace, Computation<String, Object>> memoizedTransformations =
                new Mapping<>(Collections.singletonMap(computationId, memoizedTransformation),
                        Namespace[]::new,
                        Computation[]::new);
        NamespacedRecord<Namespace, Computation<String, Object>> onDemandTransformations =
                new NamespacedRecordImpl<>(Collections.singletonMap(computationId, onDemandTransformation));

        Computing<String> computing = Computing.builder(argument)
                .withMemoizedComputations(memoizedTransformations)
                .withOnDemandComputations(onDemandTransformations)
                .build();

        Integer result = computing.compute(computationId);
        // The onDemand should take precedence
        assertEquals(1, onDemandCounter.get());
        assertEquals(0, memoizedCounter.get());

        // Calling again should recompute onDemand
        result = computing.compute(computationId);
        assertEquals(2, onDemandCounter.get());
        assertEquals(0, memoizedCounter.get());
    }
}
