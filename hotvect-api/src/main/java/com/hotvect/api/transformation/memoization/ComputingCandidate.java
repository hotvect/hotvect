package com.hotvect.api.transformation.memoization;

import com.google.common.collect.Sets;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.transformation.RankingFeatureComputationDependency;
import com.hotvect.utils.FuzzyMatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class ComputingCandidate<SHARED, ACTION> implements Computable {

    private final Computing<SHARED> computingShared;
    private final Computing<ACTION> computingAction;

    private final NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap;

    private final AtomicReference<NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>>> memoized;
    private final AtomicReference<NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>>> nonMemoized;

    private ComputingCandidate(
            NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap,
            Computing<SHARED> computingShared,
            Computing<ACTION> computingAction,
            NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> memoized,
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> nonMemoized
    ) {
        this.dependencyLookupMap = dependencyLookupMap;
        this.computingShared = computingShared;
        this.computingAction = computingAction;
        this.memoized = new AtomicReference<>(memoized != null ? memoized : NamespacedRecord.empty());
        this.nonMemoized = new AtomicReference<>(nonMemoized != null ? nonMemoized : NamespacedRecord.empty());
    }

    public Computing<ACTION> getAction() {
        return this.computingAction;
    }

    /**
     * Suggests possible computations and throws an exception. NOT performance critical
     * @param computationId
     * @return
     * @param <V>
     */
    private <V> V suggestAndThrowException(String computationId) {
        Set<String> combinedKeys = dependencyLookupMap.asMap().keySet().stream().map(Namespace::toString).collect(Collectors.toSet());
        FuzzyMatch fuzzyMatch = new FuzzyMatch(combinedKeys);
        List<String> candidates = fuzzyMatch.getClosestCandidates(computationId);

        Map<String, RankingFeatureComputationDependency> lookup = dependencyLookupMap.asMap().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));
        String candidateDependencies = candidates.stream()
                .map(candidate -> String.format("%s (depends on: %s)", candidate, lookup.get(candidate)))
                .collect(Collectors.joining(", "));

        throw new WrongTransformationDefinitionException(
                String.format(
                        "The requested computation %s cannot be found. Did you mean one of the following: %s?",
                        computationId,
                        candidateDependencies
                )
        );
    }

    @Override
    public <V> V computeIfAbsent(Namespace computationId) {
        RankingFeatureComputationDependency dependency = this.dependencyLookupMap.get(computationId);
        if (dependency == null) {
            return suggestAndThrowException(computationId.toString());
        }

        switch (dependency) {
            case SHARED:
                return this.computingShared.computeIfAbsent(computationId);
            case ACTION:
                return this.computingAction.computeIfAbsent(computationId);
            case INTERACTION:
                return doCompute(computationId);
            default:
                throw new WrongTransformationDefinitionException("Unexpected dependency: " + dependency);
        }
    }

    /**
     * Please only use this method for debugging purposes; it is very inefficient.
     *
     * @param computationId
     * @param <V>
     * @return
     */
    @Override
    public <V> V debugComputeIfAbsent(String computationId) {
        Map<String, RankingFeatureComputationDependency> byString = new HashMap<>();
        for (Map.Entry<Namespace, RankingFeatureComputationDependency> e : dependencyLookupMap.asMap().entrySet()) {
            byString.put(e.getKey().toString(), e.getValue());
        }
        RankingFeatureComputationDependency dependency = byString.get(computationId);
        if (dependency == null) {
            return suggestAndThrowException(computationId);
        }

        switch (dependency) {
            case SHARED:
                return computingShared.debugComputeIfAbsent(computationId);
            case ACTION:
                return computingAction.debugComputeIfAbsent(computationId);
            case INTERACTION:
                return doDebugCompute(computationId);
            default:
                throw new WrongTransformationDefinitionException(String.format("Unsupported dependency type: %s for computationId: %s", dependency, computationId));
        }
    }

    private <V> V doDebugCompute(String computationId) {
        Map<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> memoizedMap = this.memoized.get().asMap();
        Map<Namespace, InteractingComputation<SHARED, ACTION, Object>> nonMemoizedMap = this.nonMemoized.get().asMap();

        // Check in memoized transformations
        for (Namespace columnName : memoizedMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object> memoizedValue = memoizedMap.get(columnName);
                if (memoizedValue != null) {
                    Holder<Object> holder = memoizedValue.getCache();
                    if (holder != null) {
                        return (V) holder.value;
                    }
                    InteractingComputation<SHARED, ACTION, Object> transformation = memoizedValue.getComputation();
                    V ret = (V) transformation.apply(this);
                    return ret;
                }
            }
        }

        // Check in non-memoized transformations
        for (Namespace columnName : nonMemoizedMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                InteractingComputation<SHARED, ACTION, Object> transformation = nonMemoizedMap.get(columnName);
                if (transformation != null) {
                    V ret = (V) transformation.apply(this);
                    return ret;
                }
            }
        }

        // Suggest possible computations
        Set<String> combinedKeys = Sets.union(memoizedMap.keySet(), nonMemoizedMap.keySet()).stream()
                .map(Namespace::toString).collect(Collectors.toSet());
        FuzzyMatch fuzzyMatch = new FuzzyMatch(combinedKeys);
        List<String> closestMatch = fuzzyMatch.getClosestCandidates(computationId);

        throw new WrongTransformationDefinitionException("Was asked to perform a transformation that was not registered. You must register \"" + computationId + "\" as a memoized transformation. Did you mean:" + closestMatch);
    }

    private <V> V doCompute(Namespace computationId) {
        // Check in non-memoized transformations
        InteractingComputation<SHARED, ACTION, Object> transformation = this.nonMemoized.get().get(computationId);
        if (transformation != null) {
            var context = MemoizationStatistic.startTimer(computationId, false);
            V ret = (V) transformation.apply(this);
            if (context != null) {
                context.close();
            }
            return ret;
        }

        // Check in memoized transformations
        MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object> memoizedValue = this.memoized.get().get(computationId);
        if (memoizedValue == null) {
            // Computation not found
            suggestAndThrowException(computationId.toString());
        }

        // Check for cached value
        Holder<Object> cached = memoizedValue.getCache();
        if (cached != null) {
            return (V) cached.value;
        }

        // Perform computation and cache the result
        var context = MemoizationStatistic.startTimer(computationId, true);
        V transformed = (V) memoizedValue.getComputation().apply(this);
        if (context != null) {
            context.close();
        }

        memoizedValue.setCache(new Holder<>(transformed));

        return transformed;
    }

    public Computing<SHARED> getShared() {
        return this.computingShared;
    }

    public static <SHARED, ACTION> ComputingCandidate<SHARED, ACTION> withComputations(
            NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookup,
            Computing<SHARED> shared,
            Computing<ACTION> action,
            Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> memoizedInteractionTransformations,
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> nonMemoizedInteractionTransformations

    ) {

        // Prepare memoizations for the interaction transformations
        NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> interactionMemoizations;
        if (memoizedInteractionTransformations == null) {
            interactionMemoizations = NamespacedRecord.empty();
        } else {
            Namespace[] keys = memoizedInteractionTransformations.keys();
            InteractingComputation<SHARED, ACTION, Object>[] values = memoizedInteractionTransformations.values();
            MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>[] memos = new MemoizedValue[keys.length];
            for (int i = 0; i < keys.length; i++) {
                memos[i] = new MemoizedValue<>(values[i]);
            }
            interactionMemoizations = new NamespacedRecordImpl<>(keys, memos);
        }

        NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> nonMemoizations = nonMemoizedInteractionTransformations != null
                ? nonMemoizedInteractionTransformations
                : NamespacedRecord.empty();

        return new ComputingCandidate<>(
                dependencyLookup,
                shared,
                action,
                interactionMemoizations,
                nonMemoizations
        );
    }


    public static <SHARED, ACTION> ComputingCandidate<SHARED, ACTION> withArguments(
            NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookup,
            Computing<SHARED> shared,
            Computing<ACTION> action) {
        return withComputations(
                dependencyLookup,
                shared,
                action,
                null,
                null
        );
    }



    public static <SHARED, ACTION> ComputingCandidate<SHARED, ACTION> withComputations(
            NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookup,
            Computing<SHARED> shared,
            Computing<ACTION> action,
            Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> memoizedInteractionTransformations) {
        return withComputations(
                dependencyLookup,
                shared,
                action,
                memoizedInteractionTransformations,
                null
        );
    }

    public static <SHARED, ACTION> ComputingCandidate<SHARED, ACTION> withComputations(
            NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookup,
            Computing<SHARED> shared,
            Computing<ACTION> action,
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> nonMemoizedInteractionTransformations) {
        return withComputations(
                dependencyLookup,
                shared,
                action,
                null,
                nonMemoizedInteractionTransformations
        );
    }

    public ComputingCandidate<SHARED, ACTION> appendComputations(
            Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> memoizedInteractionTransformations,
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> nonMemoizedInteractionTransformations
    ) {

        // Append to interaction memoizations
        if (memoizedInteractionTransformations != null && memoizedInteractionTransformations.keys().length > 0) {
            NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> previous = this.memoized.get();
            NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> newRecord = previous.shallowCopy();
            Namespace[] keys = memoizedInteractionTransformations.keys();
            InteractingComputation<SHARED, ACTION, Object>[] values = memoizedInteractionTransformations.values();
            for (int i = 0; i < keys.length; i++) {
                Namespace computationId = keys[i];
                if (newRecord.get(computationId) == null) {
                    newRecord.put(computationId, new MemoizedValue<>(values[i]));
                }
            }
            checkState(this.memoized.compareAndSet(previous, newRecord), "Unexpected race condition");
        }

        if (nonMemoizedInteractionTransformations != null && !nonMemoizedInteractionTransformations.asMap().isEmpty()) {
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> previous = this.nonMemoized.get();
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> newRecord = previous.shallowCopy();
            newRecord.merge(nonMemoizedInteractionTransformations);
            checkState(this.nonMemoized.compareAndSet(previous, newRecord), "Unexpected race condition");
        }

        return this;
    }
}
