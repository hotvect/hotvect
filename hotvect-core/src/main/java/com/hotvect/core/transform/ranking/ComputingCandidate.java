package com.hotvect.core.transform.ranking;

import com.google.common.collect.Sets;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.core.transform.*;
import com.hotvect.utils.FuzzyMatch;
import com.hotvect.utils.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ComputingCandidate<SHARED, ACTION> implements Computable<Pair<RankingRequest<SHARED, ACTION>, ACTION>> {

    private final Computable<RankingRequest<SHARED, ACTION>> computingShared;
    private final Computable<ACTION> computingAction;
    private final Pair<RankingRequest<SHARED, ACTION>, ACTION> originalInput;

    private final AtomicReference<NamespacedRecord<Namespace, RankingFeatureComputationDependency>> dependencyLookupMap;

    private final AtomicReference<NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>>> memoized;
    private final AtomicReference<NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>>> onDemand;

    public ComputingCandidate(
            Computable<RankingRequest<SHARED, ACTION>> computingShared,
            Computable<ACTION> computingAction,
            NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap,
            Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> memoized,
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> onDemand
    ) {
        this.originalInput = Pair.of(computingShared.getOriginalInput(), computingAction.getOriginalInput());
        this.computingShared = computingShared;
        this.computingAction = computingAction;
        this.dependencyLookupMap = new AtomicReference<>(
                dependencyLookupMap != null ? dependencyLookupMap : NamespacedRecord.empty()
        );

        NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> memoizedRecord =
                new NamespacedRecordImpl<>();
        if (memoized != null && memoized.keys().length > 0) {
            Namespace[] keys = memoized.keys();
            InteractingComputation<SHARED, ACTION, Object>[] values = memoized.values();
            for (int i = 0; i < keys.length; i++) {
                memoizedRecord.put(keys[i], new MemoizedValue<>(values[i]));
            }
        }
        this.memoized = new AtomicReference<>(memoizedRecord);

        this.onDemand = new AtomicReference<>(onDemand != null ? onDemand : NamespacedRecord.empty());
    }

    public Computable<ACTION> getAction() {
        return this.computingAction;
    }

    /**
     * Suggests possible computations and throws an exception if a requested computation cannot be found.
     */
    private <V> V suggestAndThrowException(String computationId) {
        Set<String> combinedKeys = dependencyLookupMap.get().asMap().keySet().stream()
                .map(Namespace::toString).collect(Collectors.toSet());
        FuzzyMatch fuzzyMatch = new FuzzyMatch(combinedKeys);
        List<String> candidates = fuzzyMatch.getClosestCandidates(computationId);

        Map<String, RankingFeatureComputationDependency> lookup = new HashMap<>();
        for (Map.Entry<Namespace, RankingFeatureComputationDependency> e : dependencyLookupMap.get().asMap().entrySet()) {
            lookup.put(e.getKey().toString(), e.getValue());
        }
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
    public <V> V compute(Namespace computationId) {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> lookupMap = this.dependencyLookupMap.get();
        if (lookupMap == null) {
            return doCompute(computationId);
        }
        RankingFeatureComputationDependency dependency = lookupMap.get(computationId);
        if (dependency == null) {
            return suggestAndThrowException(computationId.toString());
        }
        return switch (dependency) {
            case SHARED -> computingShared.compute(computationId);
            case ACTION -> computingAction.compute(computationId);
            case INTERACTION -> doCompute(computationId);
            default -> throw new WrongTransformationDefinitionException("Unexpected dependency: " + dependency);
        };
    }

    @Override
    public Pair<RankingRequest<SHARED, ACTION>, ACTION> getOriginalInput() {
        return this.originalInput;
    }

    @Override
    public Computing<Pair<RankingRequest<SHARED, ACTION>, ACTION>> appendComputations(Mapping<Namespace, Computation<Pair<RankingRequest<SHARED, ACTION>, ACTION>, Object>> memoized, Mapping<Namespace, Computation<Pair<RankingRequest<SHARED, ACTION>, ACTION>, Object>> onDemand) {
        throw new UnsupportedOperationException();
    }

    /**
     * Use this method for debugging only; it is very inefficient.
     */
    @Override
    public <V> V debugCompute(String computationId) {
        RankingFeatureComputationDependency dependency = dependencyLookupMap.get().asMap().entrySet().stream()
                .filter(e -> e.getKey().toString().equals(computationId))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);

        if (dependency == null) {
            return suggestAndThrowException(computationId);
        }
        return switch (dependency) {
            case SHARED -> computingShared.debugCompute(computationId);
            case ACTION -> computingAction.debugCompute(computationId);
            case INTERACTION -> doDebugCompute(computationId);
            default -> throw new WrongTransformationDefinitionException(
                    String.format("Unsupported dependency type: %s for computationId: %s", dependency, computationId)
            );
        };
    }

    private <V> V doDebugCompute(String computationId) {
        Map<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> memoizedMap =
                this.memoized.get().asMap();
        Map<Namespace, InteractingComputation<SHARED, ACTION, Object>> onDemandMap =
                this.onDemand.get().asMap();

        // Check in memoized transformations
        for (Namespace columnName : memoizedMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object> memoizedValue = memoizedMap.get(columnName);
                if (memoizedValue != null) {
                    Holder<Object> holder = memoizedValue.getCache();
                    if (holder != null) {
                        return (V) holder.value();
                    }
                    InteractingComputation<SHARED, ACTION, Object> transformation = memoizedValue.getComputation();
                    return (V) transformation.apply(this);
                }
            }
        }

        // Check in non-memoized transformations
        for (Namespace columnName : onDemandMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                InteractingComputation<SHARED, ACTION, Object> transformation = onDemandMap.get(columnName);
                if (transformation != null) {
                    return (V) transformation.apply(this);
                }
            }
        }

        // Suggest possible computations
        Set<String> combinedKeys = Sets.union(memoizedMap.keySet(), onDemandMap.keySet()).stream()
                .map(Namespace::toString).collect(Collectors.toSet());
        FuzzyMatch fuzzyMatch = new FuzzyMatch(combinedKeys);
        List<String> closestMatch = fuzzyMatch.getClosestCandidates(computationId);

        throw new WrongTransformationDefinitionException(
                "Was asked to perform a transformation that was not registered. You must register \"" +
                        computationId + "\" as a memoized transformation. Did you mean:" + closestMatch
        );
    }

    private <V> V doCompute(Namespace computationId) {
        // Check in non-memoized transformations
        InteractingComputation<SHARED, ACTION, Object> transformation = this.onDemand.get().get(computationId);
        if (transformation != null) {
            V ret = (V) transformation.apply(this);
            return ret;
        }

        // Check in memoized transformations
        MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object> memoizedValue =
                this.memoized.get().get(computationId);
        if (memoizedValue == null) {
            // Computation not found
            return suggestAndThrowException(computationId.toString());
        }

        // Check for cached value
        Holder<Object> cached = memoizedValue.getCache();
        if (cached != null) {
            return (V) cached.value();
        }

        // Perform computation and cache the result
        V transformed = (V) memoizedValue.getComputation().apply(this);

        memoizedValue.setCache(new Holder<>(transformed));
        return transformed;
    }

    public Computable<RankingRequest<SHARED, ACTION>> getShared() {
        return this.computingShared;
    }

    /**
     * Appends new computations to the existing candidate and updates the dependency lookup map with fresh entries.
     * This is an append-only operation.
     */
    public ComputingCandidate<SHARED, ACTION> appendComputations(
            Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionMemoizedComputations,
            Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionOnDemandComputations,
            NamespacedRecord<Namespace, RankingFeatureComputationDependency> newDependencyLookupMap
    ) {
        if (interactionMemoizedComputations != null && interactionMemoizedComputations.keys().length > 0) {
            NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> previous =
                    this.memoized.get();
            NamespacedRecord<Namespace, MemoizedValue<InteractingComputation<SHARED, ACTION, Object>, Object>> newRecord =
                    previous.shallowCopy();
            Namespace[] keys = interactionMemoizedComputations.keys();
            InteractingComputation<SHARED, ACTION, Object>[] values = interactionMemoizedComputations.values();
            boolean hasUpdates = false;
            for (int i = 0; i < keys.length; i++) {
                Namespace key = keys[i];
                InteractingComputation<SHARED, ACTION, Object> value = values[i];
                hasUpdates = newRecord.putIfAbsent(key, new MemoizedValue<>(value));
            }
            if (hasUpdates) {
                this.memoized.set(newRecord);
            }
        }

        if (interactionOnDemandComputations != null && interactionOnDemandComputations.keys().length > 0) {
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> previous =
                    this.onDemand.get();
            NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> newRecord = previous.shallowCopy();
            Namespace[] keys = interactionOnDemandComputations.keys();
            InteractingComputation<SHARED, ACTION, Object>[] values = interactionOnDemandComputations.values();
            boolean hasUpdates = newRecord.putAllIfAbsent(keys, values);
            if(hasUpdates){
                this.onDemand.set(newRecord);
            }
        }

        this.dependencyLookupMap.set(newDependencyLookupMap);
        return this;
    }

    public NamespacedRecord<Namespace, RankingFeatureComputationDependency> getDependencyLookupMap() {
        return dependencyLookupMap.get();
    }
}
