package com.hotvect.api.transformation.memoization;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ComputingTest {

    // Define a test enum that implements Namespace
    enum TestNamespace implements Namespace {
        COMPUTATION1,
        COMPUTATION2,
        COMPUTATION_ONE, // Similar to COMPUTATION1
        KNOWN_COMPUTATION,
        UNKNOWN_COMPUTATION,
        COMUTATION1, // Typo of COMPUTATION1
        INTERACTION_COMPUTATION;

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String toString() {
            return name(); // in case it's used somewhere
        }
    }

    @Test
    void testWithArgument() {
        String argument = "testArgument";
        Computing<String> computing = Computing.withArgument(argument);
        assertNotNull(computing);
        assertEquals(argument, computing.getOriginalInput());
    }

    @Test
    void testComputeIfAbsentWithNonMemoizedTransformation() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();
        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoized = new NamespacedRecordImpl<>();
        nonMemoized.put(computationId, transformation);
        // Use withComputations method for non-memoized transformations
        Computing<String> computing = Computing.withComputations(argument, nonMemoized);
        Integer result = computing.computeIfAbsent(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
        // Calling again should recompute since it's non-memoized
        result = computing.computeIfAbsent(computationId);
        assertEquals(2, result.intValue());
        assertEquals(2, computationCounter.get());
    }

    @Test
    void testComputeIfAbsentWithNonMemoizedTransformation_Appending() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();
        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoized = new NamespacedRecordImpl<>();
        nonMemoized.put(computationId, transformation);
        Computing<String> computing = Computing.withArgument(argument);
        computing.appendComputations(null, nonMemoized);
        Integer result = computing.computeIfAbsent(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
        // Calling again should recompute since it's non-memoized
        result = computing.computeIfAbsent(computationId);
        assertEquals(2, result.intValue());
        assertEquals(2, computationCounter.get());
    }

    @Test
    void testComputeIfAbsentWithMemoizedTransformation() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{computationId}, new Computation[]{transformation}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        Integer result = computing.computeIfAbsent(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
        // Calling again should return cached value
        result = computing.computeIfAbsent(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
    }

    @Test
    void testComputeIfAbsentWithMemoizedTransformation_Appending() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();
        Computing<String> computing = Computing.withArgument(argument);
        // Append memoized transformation
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{computationId}, new Computation[]{transformation}
        );
        computing.appendComputations(memoizedTransformations, null);
        Integer result = computing.computeIfAbsent(computationId);
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
        // Calling again should return cached value
        result = computing.computeIfAbsent(computationId);
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
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{computationId1}, new Computation[]{transformation1}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        // Compute the first computation
        Integer result1 = computing.computeIfAbsent(computationId1);
        assertEquals(1, result1.intValue());
        assertEquals(1, computationCounter1.get());
        // Append a new memoized transformation2
        Mapping<Namespace, Computation<String, Object>> newMemoizedTransformations = new Mapping<>(
                new Namespace[]{computationId2}, new Computation[]{transformation2}
        );
        computing.appendComputations(newMemoizedTransformations, null);
        // Compute the new computation
        Integer result2 = computing.computeIfAbsent(computationId2);
        assertEquals(1, result2.intValue());
        assertEquals(1, computationCounter2.get());
        // Ensure the first computation still returns cached value
        result1 = computing.computeIfAbsent(computationId1);
        assertEquals(1, result1.intValue());
        assertEquals(1, computationCounter1.get());
        // Append non-memoized transformation
        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoized = new NamespacedRecordImpl<>();
        nonMemoized.put(TestNamespace.KNOWN_COMPUTATION, nonComputation);
        computing.appendComputations(null, nonMemoized);
        // Compute non-memoized transformation
        Integer result3 = computing.computeIfAbsent(TestNamespace.KNOWN_COMPUTATION);
        assertEquals(1, result3.intValue());
        assertEquals(1, computationCounter3.get());
        // Calling again should recompute since it's non-memoized
        result3 = computing.computeIfAbsent(TestNamespace.KNOWN_COMPUTATION);
        assertEquals(2, result3.intValue());
        assertEquals(2, computationCounter3.get());
    }

    @Test
    void testComputeIfAbsentWithPrecalculated() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        Integer precalculatedValue = 36;
        Map<Namespace, Object> precalculated = new HashMap<>();
        precalculated.put(computationId, precalculatedValue);
        Computing<String> computing = Computing.withPrecalculations(argument, precalculated);
        Integer result = computing.computeIfAbsent(computationId);
        assertEquals(precalculatedValue, result);
    }

    @Test
    void testComputeIfAbsentWithPrecalculatedAndAppendTransformations() {
        String argument = "testArgument";
        Namespace precalculatedComputationId = TestNamespace.COMPUTATION1;
        Integer precalculatedValue = 36;
        Map<Namespace, Object> precalculated = new HashMap<>();
        precalculated.put(precalculatedComputationId, precalculatedValue);
        Computing<String> computing = Computing.withPrecalculations(argument, precalculated);
        // Test that precalculated value is returned
        Integer result = computing.computeIfAbsent(precalculatedComputationId);
        assertEquals(precalculatedValue, result);
        // Append memoized transformation
        Namespace memoizedComputationId = TestNamespace.COMPUTATION2;
        AtomicInteger memoizedCounter = new AtomicInteger(0);
        Computation<String, Object> computation = comp -> memoizedCounter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{memoizedComputationId}, new Computation[]{computation}
        );
        computing.appendComputations(memoizedTransformations, null);
        // Compute memoized transformation
        Integer memoizedResult = computing.computeIfAbsent(memoizedComputationId);
        assertEquals(1, memoizedResult.intValue());
        assertEquals(1, memoizedCounter.get());
        // Calling again should return cached value
        memoizedResult = computing.computeIfAbsent(memoizedComputationId);
        assertEquals(1, memoizedResult.intValue());
        assertEquals(1, memoizedCounter.get());
        // Append non-memoized transformation
        Namespace nonMemoizedComputationId = TestNamespace.KNOWN_COMPUTATION;
        AtomicInteger nonMemoizedCounter = new AtomicInteger(0);
        Computation<String, Object> nonComputation = comp -> nonMemoizedCounter.incrementAndGet();
        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoizedTransformations = new NamespacedRecordImpl<>();
        nonMemoizedTransformations.put(nonMemoizedComputationId, nonComputation);
        computing.appendComputations(null, nonMemoizedTransformations);
        // Compute non-memoized transformation
        Integer nonMemoizedResult = computing.computeIfAbsent(nonMemoizedComputationId);
        assertEquals(1, nonMemoizedResult.intValue());
        assertEquals(1, nonMemoizedCounter.get());
        // Calling again should recompute since it's non-memoized
        nonMemoizedResult = computing.computeIfAbsent(nonMemoizedComputationId);
        assertEquals(2, nonMemoizedResult.intValue());
        assertEquals(2, nonMemoizedCounter.get());
    }

    @Test
    void testComputeIfAbsentThrowsExceptionForUnknownComputation() {
        String argument = "testArgument";
        Namespace knownComputationId = TestNamespace.KNOWN_COMPUTATION;
        Namespace unknownComputationId = TestNamespace.UNKNOWN_COMPUTATION;
        Computation<String, Object> transformation = comp -> 1;
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{knownComputationId}, new Computation[]{transformation}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computing.computeIfAbsent(unknownComputationId)
        );
        assertTrue(exception.getMessage().contains("cannot be found"));
    }

    @Test
    void testDebugComputeIfAbsent() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger computationCounter = new AtomicInteger(0);
        Computation<String, Object> transformation = comp -> computationCounter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{computationId}, new Computation[]{transformation}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        Integer result = computing.debugComputeIfAbsent(computationId.getName());
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
        // Calling again should return cached value
        result = computing.debugComputeIfAbsent(computationId.getName());
        assertEquals(1, result.intValue());
        assertEquals(1, computationCounter.get());
    }

    // Memoization performance test for Memoized
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
        Computing<String> nonComputing = Computing.withArgument(argument);
        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoizedTransformations = new NamespacedRecordImpl<>();
        nonMemoizedTransformations.put(computationId, expensiveTransformation);
        nonComputing.appendComputations(null, nonMemoizedTransformations);
        // Memoized version
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{computationId}, new Computation[]{expensiveTransformation}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        // Define the number of times to call the computation and number of threads
        int numCalls = 1000;
        int numThreads = 10;
        // Measure time for non-memoized
        ExecutorService executorNonMemoized = Executors.newFixedThreadPool(numThreads);
        List<Callable<Object>> nonMemoizedTasks = new ArrayList<>();
        for (int i = 0; i < numCalls; i++) {
            nonMemoizedTasks.add(() -> nonComputing.computeIfAbsent(computationId));
        }
        long startTimeNonMemoized = System.nanoTime();
        executorNonMemoized.invokeAll(nonMemoizedTasks);
        long durationNonMemoized = System.nanoTime() - startTimeNonMemoized;
        executorNonMemoized.shutdown();
        // Measure time for memoized
        ExecutorService executorMemoized = Executors.newFixedThreadPool(numThreads);
        List<Callable<Object>> memoizedTasks = new ArrayList<>();
        for (int i = 0; i < numCalls; i++) {
            memoizedTasks.add(() -> computing.computeIfAbsent(computationId));
        }
        long startTimeMemoized = System.nanoTime();
        executorMemoized.invokeAll(memoizedTasks);
        long durationMemoized = System.nanoTime() - startTimeMemoized;
        executorMemoized.shutdown();
        System.out.println("Non-memoized duration: " + durationNonMemoized / 1e6 + " ms");
        System.out.println("Memoized duration: " + durationMemoized / 1e6 + " ms");
        assertTrue(durationMemoized < durationNonMemoized, "Memoized computation should be faster than non-memoized computation");
    }

    @Test
    void testAppendComputations_DuplicateComputationId() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);
        // First transformation
        Computation<String, Object> transformation1 = comp -> counter1.incrementAndGet();
        // Second transformation
        Computation<String, Object> transformation2 = comp -> counter2.incrementAndGet();
        // Create Computing instance with first transformation registered under computationId
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{computationId}, new Computation[]{transformation1}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        // Attempt to append a new transformation under the same computationId
        Mapping<Namespace, Computation<String, Object>> newMemoizedTransformations = new Mapping<>(
                new Namespace[]{computationId}, new Computation[]{transformation2}
        );
        computing.appendComputations(newMemoizedTransformations, null);
        // Call computeIfAbsent on computationId
        Integer result = computing.computeIfAbsent(computationId);
        // Check that counter1 was incremented, and counter2 was not
        assertEquals(1, counter1.get(), "The first transformation should have been used");
        assertEquals(0, counter2.get(), "The second transformation should not have been used");
        // Calling computeIfAbsent again should return cached value and not increment counters
        result = computing.computeIfAbsent(computationId);
        assertEquals(1, counter1.get(), "The first transformation should have been used and result cached");
        assertEquals(0, counter2.get(), "The second transformation should not have been used");
    }

    @Test
    void testAppendNonMemoizedTransformations_DuplicateComputationId() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);
        // First non-memoized transformation
        Computation<String, Object> transformation1 = comp -> counter1.incrementAndGet();
        // Second non-memoized transformation
        Computation<String, Object> transformation2 = comp -> counter2.incrementAndGet();
        // Create Computing instance with first non-memoized transformation registered under computationId
        NamespacedRecord<Namespace, Computation<String, Object>> nonMemoizedTransformations = new NamespacedRecordImpl<>();
        nonMemoizedTransformations.put(computationId, transformation1);
        Computing<String> computing = Computing.withComputations(argument, nonMemoizedTransformations);
        // Attempt to append another non-memoized transformation under the same computationId
        NamespacedRecord<Namespace, Computation<String, Object>> newNonMemoizedTransformations = new NamespacedRecordImpl<>();
        newNonMemoizedTransformations.put(computationId, transformation2);
        computing.appendComputations(null, newNonMemoizedTransformations);
        // Call computeIfAbsent on computationId multiple times
        Integer result1 = computing.computeIfAbsent(computationId);
        Integer result2 = computing.computeIfAbsent(computationId);
        // Since it's non-memoized, the transformation should be called each time
        // But the first transformation should be used, not the second one
        // Check that counter1 was incremented twice, and counter2 was not
        assertEquals(2, counter1.get(), "The first transformation should have been used twice");
        assertEquals(0, counter2.get(), "The second transformation should not have been used");
    }

    @Test
    void testComputeIfAbsentWithPrecalculatedAndAppendDuplicate() {
        String argument = "testArgument";
        Namespace computationId = TestNamespace.COMPUTATION1;
        Integer precalculatedValue = 36;
        AtomicInteger counter = new AtomicInteger(0);
        Map<Namespace, Object> precalculated = new HashMap<>();
        precalculated.put(computationId, precalculatedValue);
        Computing<String> computing = Computing.withPrecalculations(argument, precalculated);
        // Attempt to append a transformation under the same computationId
        Computation<String, Object> transformation = comp -> counter.incrementAndGet();
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{computationId}, new Computation[]{transformation}
        );
        computing.appendComputations(memoizedTransformations, null);
        // Compute the value for computationId
        Integer result = computing.computeIfAbsent(computationId);
        // The precalculated value should be returned, transformation should not be called
        assertEquals(precalculatedValue, result);
        assertEquals(0, counter.get(), "The transformation should not have been used");
        // Ensure that even after attempting to append, the precalculated value remains
    }

    @Test
    void testSuggestAndThrowMechanism() {
        String argument = "testArgument";
        Namespace registeredComputationId = TestNamespace.COMPUTATION1;
        // Intentionally create a typo in the computation ID
        String typoComputationId = "COMPUTATOON1"; // Note the double 'O' instead of 'IO'
        Computation<String, Object> transformation = comp -> 1;
        // Register the correct computation ID
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{registeredComputationId}, new Computation[]{transformation}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computing.debugComputeIfAbsent(typoComputationId)
        );
        System.out.println("Exception message: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("Did you mean"));
        assertTrue(exception.getMessage().contains(registeredComputationId.getName()));
    }

    @Test
    void testSuggestAndThrowMechanismWithComputeIfAbsent() {
        String argument = "testArgument";
        Namespace registeredComputationId = TestNamespace.COMPUTATION1;
        Namespace unregisteredComputationId = TestNamespace.COMUTATION1; // Typo of COMPUTATION1
        Computation<String, Object> transformation = comp -> 1;
        // Register the correct computation ID
        Mapping<Namespace, Computation<String, Object>> memoizedTransformations = new Mapping<>(
                new Namespace[]{registeredComputationId}, new Computation[]{transformation}
        );
        Computing<String> computing = Computing.withComputations(argument, memoizedTransformations);
        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computing.computeIfAbsent(unregisteredComputationId)
        );
        System.out.println("Exception message: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("cannot be found"), "Exception message should indicate computation cannot be found");
        assertTrue(exception.getMessage().contains("Did you mean"), "Exception message should suggest possible computations");
        assertTrue(exception.getMessage().contains(registeredComputationId.getName()), "Exception message should suggest the registered computation ID");
    }
}