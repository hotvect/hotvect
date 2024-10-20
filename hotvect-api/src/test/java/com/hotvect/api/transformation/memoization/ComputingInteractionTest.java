package com.hotvect.api.transformation.memoization;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.transformation.RankingFeatureComputationDependency;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ComputingInteractionTest {

    // Define test namespaces
    enum TestNamespace implements Namespace {
        SHARED_COMPUTATION,
        ACTION_COMPUTATION,
        INTERACTION_COMPUTATION,
        UNKNOWN_COMPUTATION,
        COMPUTATION1,
        COMPUTATION2,
        COMPUTATION_ONE, // Similar to COMPUTATION1
        KNOWN_COMPUTATION,
        COMUTATION1; // Typo of COMPUTATION1

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String toString() {
            return name();
        }
    }

    @Test
    void testWithNoProcessing() {
        String sharedData = "sharedData";
        String actionData = "actionData";
        // Create dependency lookup map
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap = new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);
        // Create Computing instances without any transformations
        Computing<String> computingShared = Computing.withArgument(sharedData);
        Computing<String> computingAction = Computing.withArgument(actionData);
        ComputingCandidate<String, String> computingCandidate = ComputingCandidate.withArguments(
                dependencyLookupMap,
                computingShared, computingAction
        );
        // Since no transformations are added, attempting to compute should throw an exception
        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computingCandidate.computeIfAbsent(TestNamespace.INTERACTION_COMPUTATION)
        );
        assertTrue(exception.getMessage().contains("cannot be found"));
    }

    @Test
    void testComputeIfAbsentInteractionTransformation() {
        // Define shared and action data
        String sharedData = "sharedData";
        String actionData = "actionData";
        // Create dependency lookup map
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap = new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.SHARED_COMPUTATION, RankingFeatureComputationDependency.SHARED);
        dependencyLookupMap.put(TestNamespace.ACTION_COMPUTATION, RankingFeatureComputationDependency.ACTION);
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);
        // Create computingShared with a memoized transformation
        AtomicInteger sharedCounter = new AtomicInteger(0);
        Computation<String, Object> sharedTransformation = comp -> {
            sharedCounter.incrementAndGet();
            return "sharedResult";
        };
        Mapping<Namespace, Computation<String, Object>> sharedMemoizedTransformations = new Mapping<>(
                new Namespace[]{TestNamespace.SHARED_COMPUTATION}, new Computation[]{sharedTransformation}
        );
        Computing<String> computingShared = Computing.withComputations(sharedData, sharedMemoizedTransformations);
        // Create computingAction with a memoized transformation
        AtomicInteger actionCounter = new AtomicInteger(0);
        Computation<String, Object> actionTransformation = comp -> {
            actionCounter.incrementAndGet();
            return "actionResult";
        };
        Mapping<Namespace, Computation<String, Object>> actionMemoizedTransformations = new Mapping<>(
                new Namespace[]{TestNamespace.ACTION_COMPUTATION}, new Computation[]{actionTransformation}
        );
        Computing<String> computingAction = Computing.withComputations(actionData, actionMemoizedTransformations);
        // Create memoized interaction transformation
        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            String sharedResult = (String) interactionComp.getShared()
                    .computeIfAbsent(TestNamespace.SHARED_COMPUTATION);
            String actionResult = (String) interactionComp.getAction()
                    .computeIfAbsent(TestNamespace.ACTION_COMPUTATION);
            return sharedResult + "-" + actionResult + "-interactionResult";
        };
        Mapping<Namespace, InteractingComputation<String, String, Object>> interactionMemoizedTransformations = new Mapping<>(
                new Namespace[]{TestNamespace.INTERACTION_COMPUTATION}, new InteractingComputation[]{interactionTransformation}
        );
        // Create ComputingCandidate instance with memoized interaction transformation
        ComputingCandidate<String, String> computingCandidate = ComputingCandidate.withComputations(
                dependencyLookupMap,
                computingShared,
                computingAction,
                interactionMemoizedTransformations,
                null // No non-memoized interaction transformations
        );
        // Compute the interaction computation
        String interactionResult = (String) computingCandidate.computeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("sharedResult-actionResult-interactionResult", interactionResult);
        // Verify counters
        assertEquals(1, sharedCounter.get(), "Shared transformation should have been called once");
        assertEquals(1, actionCounter.get(), "Action transformation should have been called once");
        assertEquals(1, interactionCounter.get(), "Interaction transformation should have been called once");
        // Calling again should use memoized values
        interactionResult = (String) computingCandidate.computeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("sharedResult-actionResult-interactionResult", interactionResult);
        // Counters should not increase
        assertEquals(1, sharedCounter.get(), "Shared transformation should not have been called again");
        assertEquals(1, actionCounter.get(), "Action transformation should not have been called again");
        assertEquals(1, interactionCounter.get(), "Interaction transformation should not have been called again");
    }

    @Test
    void testComputeIfAbsentWithNonMemoizedInteractionTransformation() {
        // Define shared and action data
        String sharedData = "sharedData";
        String actionData = "actionData";
        // Create dependency lookup map
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap = new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);
        // Create computingShared and computingAction without transformations
        Computing<String> computingShared = Computing.withArgument(sharedData);
        Computing<String> computingAction = Computing.withArgument(actionData);
        // Create non-memoized interaction transformation
        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            return "interactionResult";
        };
        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> interactionNonMemoizedTransformations = new NamespacedRecordImpl<>();
        interactionNonMemoizedTransformations.put(TestNamespace.INTERACTION_COMPUTATION, interactionTransformation);
        // Create ComputingCandidate instance with non-memoized interaction transformation
        ComputingCandidate<String, String> computingCandidate = ComputingCandidate.withComputations(
                dependencyLookupMap,
                computingShared,
                computingAction,
                null, // No memoized interaction transformations
                interactionNonMemoizedTransformations // Non-memoized interaction transformations
        );
        // Compute the interaction computation
        String interactionResult = (String) computingCandidate.computeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        // Verify counter
        assertEquals(1, interactionCounter.get(), "Interaction transformation should have been called once");
        // Calling again should recompute since it's non-memoized
        interactionResult = (String) computingCandidate.computeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        assertEquals(2, interactionCounter.get(), "Interaction transformation should have been called again");
    }

    @Test
    void testAppendComputations() {
        // Define shared and action data
        String sharedData = "sharedData";
        String actionData = "actionData";
        // Create dependency lookup map
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap = new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);
        // Create computingShared and computingAction
        Computing<String> computingShared = Computing.withArgument(sharedData);
        Computing<String> computingAction = Computing.withArgument(actionData);
        // Create ComputingCandidate instance without any interaction transformations
        ComputingCandidate<String, String> computingCandidate = ComputingCandidate.withArguments(
                dependencyLookupMap, computingShared, computingAction
        );
        // Append memoized interaction transformation
        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            return "interactionResult";
        };
        Mapping<Namespace, InteractingComputation<String, String, Object>> interactionMemoizedTransformations = new Mapping<>(
                new Namespace[]{TestNamespace.INTERACTION_COMPUTATION}, new InteractingComputation[]{interactionTransformation}
        );
        computingCandidate.appendComputations(
                interactionMemoizedTransformations,
                null // No non-memoized interaction transformations
        );
        // Compute the interaction computation
        String interactionResult = (String) computingCandidate.computeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        assertEquals(1, interactionCounter.get(), "Interaction transformation should have been called once");
        // Calling again should use memoized value
        interactionResult = (String) computingCandidate.computeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION);
        assertEquals("interactionResult", interactionResult);
        assertEquals(1, interactionCounter.get(), "Interaction transformation should not have been called again");
    }

    @Test
    void testComputeIfAbsentUnknownComputation() {
        // Define shared and action data
        String sharedData = "sharedData";
        String actionData = "actionData";
        // Create dependency lookup map without the UNKNOWN_COMPUTATION
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap = new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.SHARED_COMPUTATION, RankingFeatureComputationDependency.SHARED);
        dependencyLookupMap.put(TestNamespace.ACTION_COMPUTATION, RankingFeatureComputationDependency.ACTION);
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);
        // Create computingShared and computingAction
        Computing<String> computingShared = Computing.withArgument(sharedData);
        Computing<String> computingAction = Computing.withArgument(actionData);
        // Create ComputingCandidate instance
        ComputingCandidate<String, String> computingCandidate = ComputingCandidate.withArguments(
                dependencyLookupMap, computingShared, computingAction
        );
        // Try to compute an unknown computation
        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computingCandidate.computeIfAbsent(TestNamespace.UNKNOWN_COMPUTATION)
        );
        // Verify exception message contains suggestion
        String message = exception.getMessage();
        System.out.println("Exception message: " + message);
        assertTrue(message.contains("The requested computation UNKNOWN_COMPUTATION cannot be found"));
        assertTrue(message.contains("Did you mean"));
    }

    @Test
    void testDebugComputeIfAbsent() {
        // Define shared and action data
        String sharedData = "sharedData";
        String actionData = "actionData";
        // Create dependency lookup map
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap = new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.INTERACTION_COMPUTATION, RankingFeatureComputationDependency.INTERACTION);
        // Create computingShared and computingAction
        Computing<String> computingShared = Computing.withArgument(sharedData);
        Computing<String> computingAction = Computing.withArgument(actionData);
        // Create non-memoized interaction transformation
        AtomicInteger interactionCounter = new AtomicInteger(0);
        InteractingComputation<String, String, Object> interactionTransformation = interactionComp -> {
            interactionCounter.incrementAndGet();
            return "interactionResult";
        };
        NamespacedRecord<Namespace, InteractingComputation<String, String, Object>> interactionNonMemoizedTransformations = new NamespacedRecordImpl<>();
        interactionNonMemoizedTransformations.put(TestNamespace.INTERACTION_COMPUTATION, interactionTransformation);
        // Create ComputingCandidate instance with non-memoized interaction transformation
        ComputingCandidate<String, String> computingCandidate = ComputingCandidate.withComputations(
                dependencyLookupMap,
                computingShared,
                computingAction,
                null, // No memoized interaction transformations
                interactionNonMemoizedTransformations
        );
        // Use debugComputeIfAbsent
        String result = (String) computingCandidate.debugComputeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION.toString());
        assertEquals("interactionResult", result);
        assertEquals(1, interactionCounter.get(), "Interaction transformation should have been called once");
        // Calling again should recompute since it's non-memoized
        result = (String) computingCandidate.debugComputeIfAbsent(
                TestNamespace.INTERACTION_COMPUTATION.toString());
        assertEquals("interactionResult", result);
        assertEquals(2, interactionCounter.get(), "Interaction transformation should have been called again");
    }

    @Test
    void testSuggestAndThrowMechanism() {
        // Define shared and action data
        String sharedData = "sharedData";
        String actionData = "actionData";
        // Define a computation ID with a typo
        String typoComputationId = "COMPUTATOON1"; // Typo in the computation name
        // Create dependency lookup map with the correct computation
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap = new NamespacedRecordImpl<>();
        dependencyLookupMap.put(TestNamespace.COMPUTATION1, RankingFeatureComputationDependency.INTERACTION);
        // Create computingShared and computingAction
        Computing<String> computingShared = Computing.withArgument(sharedData);
        Computing<String> computingAction = Computing.withArgument(actionData);
        // Create ComputingCandidate instance
        ComputingCandidate<String, String> computingCandidate = ComputingCandidate.withArguments(
                dependencyLookupMap, computingShared, computingAction
        );
        // Attempt to compute with the typo computation ID
        WrongTransformationDefinitionException exception = assertThrows(
                WrongTransformationDefinitionException.class,
                () -> computingCandidate.debugComputeIfAbsent(typoComputationId)
        );
        // Verify exception message contains suggestion
        String message = exception.getMessage();
        System.out.println("Exception message: " + message);
        assertTrue(message.contains("Did you mean"));
        assertTrue(message.contains(TestNamespace.COMPUTATION1.getName()), "Exception should suggest the correct computation ID");
    }

    // Adjusted unit tests to match the methods in the provided source code.
    // Used Computing.withArgument(), Computing.withComputations(), ComputingCandidate.withArguments(), etc.
    // Ensured that method calls and signatures correspond to those in the provided ComputingCandidate class.
}