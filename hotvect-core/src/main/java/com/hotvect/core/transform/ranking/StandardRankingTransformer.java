package com.hotvect.core.transform.ranking;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.core.transform.*;
import com.hotvect.core.util.Utils;
import com.hotvect.utils.FuzzyMatch;
import com.hotvect.utils.ListTransform;
import com.hotvect.utils.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

import static com.google.common.base.Preconditions.*;

@SuppressWarnings({"rawtypes"})
public class StandardRankingTransformer<SHARED, ACTION> implements ComputingRankingTransformer<SHARED, ACTION> {
    private static final Logger log = LoggerFactory.getLogger(StandardRankingTransformer.class);

    private final Mapping<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>> sharedMemoizedComputations;
    private final NamespacedRecord<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>> sharedNonMemoizedComputations;
    private final Mapping<Namespace, Computation<ACTION, Object>> actionMemoizedComputations;
    private final NamespacedRecord<Namespace, Computation<ACTION, Object>> actionNonMemoizedComputations;
    private final Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionMemoizedComputations;
    private final NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionNonMemoizedComputations;

    private final Mapping<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>> sharedNonMemoizedMapping;
    private final Mapping<Namespace, Computation<ACTION, Object>> actionNonMemoizedMapping;
    private final Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionNonMemoizedMapping;
    private final Mapping<Namespace, RankingFeatureComputationDependency> dependencyLookupMapping;

    private final EagerRankingTransformation<SHARED, ACTION> eagerTransformation;
    private final NamespacedRecord<Namespace, Holder<Object>> precomputedShared;
    private final NamespacedRecord<Namespace, ComputingBulkScorer<SHARED, ACTION>> bulkScorers;
    private final EnumMap<RankingFeatureComputationDependency, Namespace[]> computationFeatures;
    private final NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap;
    private final Namespace[] algorithmFeatures;
    private final SortedSet<Namespace> usedFeatures;

    public static class Builder<SHARED, ACTION> {
        private final Map<String, Computation> sharedComputations = new HashMap<>();
        private final Map<String, Computation> actionComputations = new HashMap<>();
        private final Map<String, InteractingComputation> interactionComputations = new HashMap<>();
        private final Map<String, ComputingBulkScorer<SHARED, ACTION>> bulkScorers = new HashMap<>();
        private final Map<String, ComputationSpec> namespeceComputationSpec = new HashMap<>();
        private final Map<String, Namespace> namespaceDictionary = new HashMap<>();
        private final Set<String> usedFeatures = new HashSet<>();
        private final Map<String, Object> sharedPrecomputations = new HashMap<>();
        private EagerRankingTransformation<SHARED, ACTION> eagerTransformation;

        private Builder() {
        }

        public <V> Builder<SHARED, ACTION> withSharedComputation(Namespace namespace, Computation<RankingRequest<SHARED, ACTION>, V> computation) {
            this.sharedComputations.put(namespace.toString(), computation);
            this.namespaceDictionary.put(namespace.toString(), namespace);
            this.namespeceComputationSpec.put(namespace.toString(), ComputationSpec.LAZY_MEMOIZED);
            return this;
        }


        public <V> Builder<SHARED, ACTION> withSharedPrecomputation(Namespace namespace, V precomputed) {
            this.sharedPrecomputations.put(namespace.toString(), precomputed);
            this.namespaceDictionary.put(namespace.toString(), namespace);
            this.namespeceComputationSpec.put(namespace.toString(), ComputationSpec.PRECOMPUTED);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withActionComputation(Namespace namespace, Computation<ACTION, V> computation) {
            String namespaceString = namespace.toString();
            this.actionComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            this.namespeceComputationSpec.put(namespace.toString(), ComputationSpec.LAZY_ON_DEMAND);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withActionComputation(Namespace namespace, Computation<ACTION, V> computation, ComputationSpec computationSpec) {
            String namespaceString = namespace.toString();
            this.actionComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            this.namespeceComputationSpec.put(namespaceString, computationSpec);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withInteractionComputation(Namespace namespace, InteractingComputation<SHARED, ACTION, V> computation) {
            this.interactionComputations.put(namespace.toString(), computation);
            this.namespaceDictionary.put(namespace.toString(), namespace);
            this.namespeceComputationSpec.put(namespace.toString(), ComputationSpec.LAZY_ON_DEMAND);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withInteractionComputation(Namespace namespace, InteractingComputation<SHARED, ACTION, V> computation, ComputationSpec computationSpec) {
            String namespaceString = namespace.toString();
            this.interactionComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            this.namespeceComputationSpec.put(namespaceString, computationSpec);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withSharedComputation(Namespace namespace, Computation<RankingRequest<SHARED, ACTION>, V> computation, boolean cached) {
            String namespaceString = namespace.toString();
            this.sharedComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            this.namespeceComputationSpec.put(namespace.toString(), cached ? ComputationSpec.LAZY_MEMOIZED : ComputationSpec.LAZY_ON_DEMAND);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withSharedComputation(Namespace namespace, Computation<RankingRequest<SHARED, ACTION>, V> computation, ComputationSpec computationSpec) {
            String namespaceString = namespace.toString();
            this.sharedComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            this.namespeceComputationSpec.put(namespaceString, computationSpec);
            return this;
        }



        public <V> Builder<SHARED, ACTION> withActionComputation(Namespace namespace, Computation<ACTION, V> computation, boolean cached) {
            String namespaceString = namespace.toString();
            this.actionComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            this.namespeceComputationSpec.put(namespace.toString(), cached ? ComputationSpec.LAZY_MEMOIZED : ComputationSpec.LAZY_ON_DEMAND);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withInteractionComputation(Namespace namespace, InteractingComputation<SHARED, ACTION, V> computation, boolean cached) {
            String namespaceString = namespace.toString();
            this.interactionComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            this.namespeceComputationSpec.put(namespace.toString(), cached ? ComputationSpec.LAZY_MEMOIZED : ComputationSpec.LAZY_ON_DEMAND);
            return this;
        }

        public Builder<SHARED, ACTION> withBulkScorer(Namespace namespace, ComputingBulkScorer<SHARED, ACTION> bulkScorer) {
            this.bulkScorers.put(namespace.toString(), bulkScorer);
            this.namespaceDictionary.put(namespace.toString(), namespace);
            return this;
        }

        public Builder<SHARED, ACTION> withFeature(String namespace) {
            this.usedFeatures.add(namespace);
            return this;
        }

        public Builder<SHARED, ACTION> setComputationSpec(String namespace, ComputationSpec computationSpec) {
            Namespace ns = findNamespace(namespace);
            checkState(this.namespeceComputationSpec.get(namespace) != ComputationSpec.PRECOMPUTED, "Namespace: " + ns + " is set as PRECOMPUTED and cannot be set as " + computationSpec);
            switch (computationSpec) {
                case PRECOMPUTED ->
                        throw new IllegalArgumentException("ComputationSpec.PRECOMPUTED is not allowed to be set manually. It is set automatically by the builder. Violating namespace: " + ns);
                case LAZY_ON_DEMAND ->
                        this.namespeceComputationSpec.put(namespace, ComputationSpec.LAZY_ON_DEMAND);
                case LAZY_MEMOIZED ->
                        this.namespeceComputationSpec.put(namespace, ComputationSpec.LAZY_MEMOIZED);
            }
            return this;
        }

        public Builder<SHARED, ACTION> setComputationSpec(Namespace namespace, ComputationSpec computationSpec) {
            checkState(this.namespeceComputationSpec.containsKey(namespace.toString()), "Namespace: " + namespace + " is not registered in the builder. Please register it first");
            return setComputationSpec(namespace.toString(), computationSpec);
        }

        public Builder<SHARED, ACTION> withEagerTransformation(EagerRankingTransformation<SHARED, ACTION> eagerTransformation) {
            this.eagerTransformation = eagerTransformation;
            return this;
        }

        private Namespace findNamespace(String namespace) {
            if (this.namespaceDictionary.containsKey(namespace)) {
                return this.namespaceDictionary.get(namespace);
            } else {
                throw generateNamespaceNotFoundError(namespace);
            }
        }

        public StandardRankingTransformer<SHARED, ACTION> build() {
            Map<Namespace, ComputingBulkScorer<SHARED, ACTION>> bulkScorer = asNamespaceMap(bulkScorers);
            for (Namespace namespace : bulkScorer.keySet()) {
                if(namespace.getFeatureValueType() == null){
                    throw new IllegalArgumentException("Namespace: " + namespace + " is not a ready to be used as a feature, because it does not have a feature " + ValueType.class.getSimpleName() + " set.");
                }
            }

            Set<Namespace> usedFeatures = asNamespaceSet(this.usedFeatures);
            SortedSet<Namespace> sortedUsedFeatures = new TreeSet<>(Namespace.alphabetical());
            for (Namespace usedFeature : usedFeatures) {
                if (usedFeature.getFeatureValueType() == null) {
                    throw new IllegalArgumentException("Namespace: " + usedFeature + " is not a ready to be used as a feature, because it does not have a feature " + ValueType.class.getSimpleName() + " set.");
                }
                sortedUsedFeatures.add(usedFeature);
            }

            return new StandardRankingTransformer<>(
                    asNamespaceMap(sharedComputations),
                    asNamespaceMap(actionComputations),
                    asNamespaceMap(interactionComputations),
                    bulkScorer,
                    asNamespaceMap(namespeceComputationSpec),
                    asNamespaceMap(sharedPrecomputations),
                    sortedUsedFeatures,
                    eagerTransformation
            );
        }

        private <K, V> Map<K, V> asNamespaceMap(Map<String, V> source) {
            Map<K, V> ret = new IdentityHashMap<>();
            for (Map.Entry<String, V> entry : source.entrySet()) {
                K namespace = (K) this.namespaceDictionary.get(entry.getKey());
                checkArgument(ret.put(namespace, entry.getValue()) == null);
            }
            return ret;
        }

        private <K> Set<K> asNamespaceSet(Set<String> source) {
            Set<K> ret = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String entry : source) {
                K namespace = (K) this.namespaceDictionary.get(entry);
                if (namespace == null) {
                    throw generateNamespaceNotFoundError(entry);
                }
                checkArgument(ret.add(namespace));
            }
            return ret;
        }

        private RuntimeException generateNamespaceNotFoundError(String entry) {
            List<String> suggestedNamespace = getSuggestedNamespace(entry);
            return new NamespaceNotFoundException(
                    "Namespace: " + entry + " is not registered in this transformer. Did you perhaps mean:" + suggestedNamespace + "?");
        }

        private List<String> getSuggestedNamespace(String entry) {
            FuzzyMatch fuzzyMatch = new FuzzyMatch(this.namespaceDictionary.keySet());
            return fuzzyMatch.getClosestCandidates(entry);
        }
    }

    public static <SHARED, ACTION> Builder<SHARED, ACTION> builder() {
        return new Builder<>();
    }

    /**
     * Constructor for {@code MemoizingRankingTransformer}.
     */
    @SuppressWarnings("unchecked")
    private StandardRankingTransformer(
            Map<Namespace, Computation> sharedComputations,
            Map<Namespace, Computation> actionComputations,
            Map<Namespace, InteractingComputation> interactionComputations,
            Map<Namespace, ComputingBulkScorer<SHARED, ACTION>> bulkScorers,
            Map<Namespace, ComputationSpec> computationSpec,
            Map<Namespace, Object> precomputedShared,
            SortedSet<Namespace> usedFeatures,
            EagerRankingTransformation<SHARED, ACTION> eagerTransformation
    ) {
        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(sharedComputations.keySet());

        // Memoized / NonMemoized - Shared
        Map<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>> sharedMemoMap =
                (Map<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>>) (Map<?, ?>) ImmutableMap.copyOf(
                        Maps.filterEntries(sharedComputations, e ->
                                EnumSet.of(ComputationSpec.LAZY_MEMOIZED)
                                        .contains(computationSpec.get(e.getKey()))));
        this.sharedMemoizedComputations = new Mapping<>(sharedMemoMap, Namespace[]::new, Computation[]::new);

        Map<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>> sharedNonMemoMap =
                (Map<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>>) (Map<?, ?>) ImmutableMap.copyOf(
                        Maps.filterEntries(sharedComputations, x ->
                                computationSpec.get(x.getKey()) == ComputationSpec.LAZY_ON_DEMAND));
        this.sharedNonMemoizedComputations = new NamespacedRecordImpl<>(sharedNonMemoMap);

        this.eagerTransformation = eagerTransformation;

        // Precomputed - Shared
        Map<Namespace, Object> sharedPrecomputedMap = Maps.filterEntries(precomputedShared,
                e -> computationSpec.get(e.getKey()) == ComputationSpec.PRECOMPUTED);
        this.precomputedShared = new NamespacedRecordImpl<>(Maps.transformValues(sharedPrecomputedMap, Holder::new));

        // Memoized / NonMemoized - Action
        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(actionComputations.keySet());
        Map<Namespace, Computation<ACTION, Object>> actionMemoMap =
                (Map<Namespace, Computation<ACTION, Object>>) (Map<?, ?>) ImmutableMap.copyOf(
                        Maps.filterEntries(actionComputations, e ->
                                computationSpec.get(e.getKey()) == ComputationSpec.LAZY_MEMOIZED));
        this.actionMemoizedComputations = new Mapping<>(actionMemoMap, Namespace[]::new, Computation[]::new);

        Map<Namespace, Computation<ACTION, Object>> actionNonMemoMap =
                (Map<Namespace, Computation<ACTION, Object>>) (Map<?, ?>) ImmutableMap.copyOf(
                        Maps.filterEntries(actionComputations, x ->
                                computationSpec.get(x.getKey()) == ComputationSpec.LAZY_ON_DEMAND));
        this.actionNonMemoizedComputations = new NamespacedRecordImpl<>(actionNonMemoMap);

        // Memoized / NonMemoized - Interaction
        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(interactionComputations.keySet());
        Map<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionMemoMap =
                (Map<Namespace, InteractingComputation<SHARED, ACTION, Object>>) (Map<?, ?>) ImmutableMap.copyOf(
                        Maps.filterEntries(interactionComputations, e ->
                                computationSpec.get(e.getKey()) == ComputationSpec.LAZY_MEMOIZED));
        this.interactionMemoizedComputations = new Mapping<>(interactionMemoMap, Namespace[]::new, InteractingComputation[]::new);

        Map<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionNonMemoMap =
                (Map<Namespace, InteractingComputation<SHARED, ACTION, Object>>) (Map<?, ?>) ImmutableMap.copyOf(
                        Maps.filterEntries(interactionComputations, x ->
                                computationSpec.get(x.getKey()) == ComputationSpec.LAZY_ON_DEMAND));
        this.interactionNonMemoizedComputations = new NamespacedRecordImpl<>(interactionNonMemoMap);

        // Additional Mappings for NonMemoized sets
        this.sharedNonMemoizedMapping = new Mapping<>(
                sharedNonMemoMap,
                Namespace[]::new,
                Computation[]::new
        );
        this.actionNonMemoizedMapping = new Mapping<>(
                actionNonMemoMap,
                Namespace[]::new,
                Computation[]::new
        );
        this.interactionNonMemoizedMapping = new Mapping<>(
                interactionNonMemoMap,
                Namespace[]::new,
                InteractingComputation[]::new
        );

        // BulkScorers
        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(bulkScorers.keySet());
        this.bulkScorers = new NamespacedRecordImpl<>(bulkScorers);

        this.usedFeatures = ImmutableSortedSet.copyOf(Namespace.alphabetical(), usedFeatures);

        // dependencyLookupMap
        this.dependencyLookupMap = toDependencyLookupMap(
                sharedComputations,
                actionComputations,
                interactionComputations,
                bulkScorers,
                precomputedShared
        );
        this.dependencyLookupMapping = new Mapping<>(
                this.dependencyLookupMap.asMap(),
                Namespace[]::new,
                RankingFeatureComputationDependency[]::new
        );

        // Features
        EnumMap<RankingFeatureComputationDependency, SortedSet<Namespace>> computationFeatures =
                new EnumMap<>(RankingFeatureComputationDependency.class);
        SortedSet<Namespace> algorithmFeatures = new TreeSet<>(Namespace.alphabetical());
        for (Namespace usedFeature : this.usedFeatures) {
            RankingFeatureComputationDependency dependency = checkNotNull(dependencyLookupMap.get(usedFeature));
            SortedSet<Namespace> featureSet;
            if (dependency == RankingFeatureComputationDependency.STACKING) {
                featureSet = algorithmFeatures;
            } else {
                featureSet = computationFeatures.computeIfAbsent(dependency, x -> new TreeSet<>(Namespace.alphabetical()));
            }
            featureSet.add(usedFeature);
        }
        if (computationFeatures.isEmpty()) {
            this.computationFeatures = null;
        } else {
            this.computationFeatures = new EnumMap<>(Maps.transformValues(
                    computationFeatures,
                    v -> v.toArray(new Namespace[0])
            ));
        }
        this.algorithmFeatures = algorithmFeatures.toArray(new Namespace[0]);
        if ((this.computationFeatures == null || this.computationFeatures.isEmpty()) && this.algorithmFeatures.length == 0) {
            log.warn("No features were registered for this transformer. This makes only sense for debugging");
        }
    }

    private static NamespacedRecord<Namespace, RankingFeatureComputationDependency> toDependencyLookupMap(
            Map<Namespace, Computation> sharedComputations,
            Map<Namespace, Computation> actionComputations,
            Map<Namespace, InteractingComputation> interactionComputations,
            Map<Namespace, ?> bulkScorers,
            Map<Namespace, Object> precomputedShared
    ) {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> ret = new NamespacedRecordImpl<>();
        sharedComputations.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.SHARED));
        actionComputations.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.ACTION));
        interactionComputations.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.INTERACTION));
        bulkScorers.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.STACKING));
        precomputedShared.keySet().forEach(namespace ->
                ret.put(namespace, RankingFeatureComputationDependency.SHARED)
        );
        return ret;
    }

    private static Map<String, Object> checkAndAdd(NamespacedRecord<Namespace, Object> transformed, Map<String, Object> additionalProperties, Namespace namespace, Object computationResult) {
        if(computationResult == null){
            return additionalProperties;
        }

        if(computationResult instanceof Result.Failure<?, ?> failure){
            if (additionalProperties == null){
                additionalProperties = new HashMap<>();
            }
            additionalProperties.put(namespace.toString(), failure.error());
        } else {
            transformed.put(namespace, computationResult);
        }
        return additionalProperties;
    }

    @Override
    public List<TransformedAction<ACTION>> transform(ComputingRankingRequest<SHARED, ACTION> input) {
        List<TransformedAction<ACTION>> ret = new ArrayList<>(input.candidates().size());
        for (ComputingCandidate<SHARED, ACTION> candidate : input.candidates()) {
            NamespacedRecord<Namespace, Object> transformed = new NamespacedRecordImpl<>();

            Map<String, Object> additionalProperties = null;

            if (this.computationFeatures != null) {
                Namespace[] sharedFeatures = this.computationFeatures.get(RankingFeatureComputationDependency.SHARED);
                if (sharedFeatures != null) {
                    for (Namespace feature : sharedFeatures) {
                        Object computationResult = candidate.getShared().compute(feature);
                        additionalProperties = checkAndAdd(transformed, additionalProperties, feature, computationResult);
                    }
                }
                Namespace[] actionFeatures = this.computationFeatures.get(RankingFeatureComputationDependency.ACTION);
                if (actionFeatures != null) {
                    for (Namespace feature : actionFeatures) {
                        Object computationResult = candidate.getAction().compute(feature);
                        additionalProperties = checkAndAdd(transformed, additionalProperties, feature, computationResult);
                    }
                }
                Namespace[] interactionFeatures = this.computationFeatures.get(RankingFeatureComputationDependency.INTERACTION);
                if (interactionFeatures != null) {
                    for (Namespace feature : interactionFeatures) {
                        Object computationResult = candidate.compute(feature);
                        additionalProperties = checkAndAdd(transformed, additionalProperties, feature, computationResult);
                    }
                }
            }

            if(this.algorithmFeatures != null){
                for (Namespace Namespace : this.algorithmFeatures) {
                    ComputingBulkScorer<SHARED, ACTION> bulkScorer = this.bulkScorers.get(Namespace);
                    List<ScoringDecision<ACTION>> scores = bulkScorer.bulkScore(input);
                    for (ScoringDecision<ACTION> decision : scores) {
                        double score = decision.score();
                        if (!decision.additionalProperties().isEmpty()) {
                            if (additionalProperties == null) {
                                additionalProperties = new HashMap<>();
                            }
                            additionalProperties.putAll(decision.additionalProperties());
                        }
                        transformed.put(Namespace, score);
                    }
                }
            }
            ret.add(TransformedAction.of(candidate.getAction().getOriginalInput(), transformed, additionalProperties == null ? ImmutableMap.of() : additionalProperties));
        }
        return ret;
    }

    @Override
    public SortedSet<Namespace> getUsedFeatures() {
        return usedFeatures;
    }

    @Override
    public ComputingRankingRequest<SHARED, ACTION> prepare(String exampleId, SHARED shared, List<Computable<ACTION>> actions) {
        RankingRequest<SHARED, ACTION> rankingRequest = new RankingRequest<>(
                exampleId,
                shared,
                ListTransform.map(actions, Computable::getOriginalInput)
        );

        NamespacedRecord<Namespace, Holder<Object>> precomputed;
        if(this.eagerTransformation == null){
            // No eager transformation, so we can just use the precomputations
            precomputed = this.precomputedShared;
        } else {
            // We have a eager transformation, perform that and put it into precomputation map
            Map<Namespace, Object> eagerTransformed = this.eagerTransformation.apply(rankingRequest);
            for (Namespace namespace : eagerTransformed.keySet()) {
                if(namespace.getFeatureValueType() != null){
                    throw new UnsupportedOperationException(
                            "Due to technical limitation, eager transformations cannot produce feature values directly. " +
                                    " please register lazy computation that uses it to define the feature:" + namespace
                            );
                }
            }
            NamespacedRecord<Namespace, Holder<Object>> toPopulate = this.precomputedShared.shallowCopy();
            for (Map.Entry<Namespace, Object> e : eagerTransformed.entrySet()) {
                toPopulate.put(e.getKey(), new Holder<>(e.getValue()));
            }
            precomputed = toPopulate;
        }
        Computing<RankingRequest<SHARED, ACTION>> computingShared = Computing.builder(new RankingRequest<>(exampleId, shared, ListTransform.map(actions, Computable::getOriginalInput)))
                .withPrecalculated(precomputed)
                .withMemoizedComputations((Mapping<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>>)(Mapping)sharedMemoizedComputations)
                .withOnDemandComputations((NamespacedRecord<Namespace,Computation<RankingRequest<SHARED, ACTION>, Object>>)(NamespacedRecord)sharedNonMemoizedComputations)
                .build();
        return prepareInternal(
                precomputed, rankingRequest,
                computingShared,
                actions
        );
    }

    /**
     * This method is called once per one request (splitting of candidates not allowed)
     * @param rankingRequest
     * @return
     */
    @Override
    public ComputingRankingRequest<SHARED, ACTION> prepare(RankingRequest<SHARED, ACTION> rankingRequest) {
        NamespacedRecord<Namespace, Holder<Object>> precomputed;
        if(this.eagerTransformation == null){
            // No eager transformation, so we can just use the precomputations
            precomputed = this.precomputedShared;
        } else {
            // We have a eager transformation, perform that and put it into precomputation map
            Map<Namespace, Object> eagerTransformed = this.eagerTransformation.apply(rankingRequest);
            NamespacedRecord<Namespace, Holder<Object>> toPopulate = this.precomputedShared.shallowCopy();
            for (Map.Entry<Namespace, Object> e : eagerTransformed.entrySet()) {
                toPopulate.put(e.getKey(), new Holder<>(e.getValue()));
            }
            precomputed = toPopulate;
        }


        Computing<RankingRequest<SHARED, ACTION>> computingShared = Computing.builder(rankingRequest)
                .withPrecalculated(precomputed)
                .withMemoizedComputations((Mapping<Namespace,Computation<RankingRequest<SHARED, ACTION>, Object>>)(Mapping)sharedMemoizedComputations)
                .withOnDemandComputations((NamespacedRecord<Namespace,Computation<RankingRequest<SHARED, ACTION>, Object>>)(NamespacedRecord)sharedNonMemoizedComputations)
                .build();
        List<Computable<ACTION>> computingActions = ListTransform.map(
                rankingRequest.availableActions(),
                action -> Computing.builder(action)
                        .withMemoizedComputations(actionMemoizedComputations)
                        .withOnDemandComputations(actionNonMemoizedComputations)
                        .build()
        );
        return prepareInternal(
                precomputed,
                rankingRequest,
                computingShared,
                computingActions
        );
    }

    /**
     * This method is called once per one request (splitting of candidates not allowed)
     * Also, note that it modifies the argument - this is safe because calculations are only appended
     *
     * @param computingRankingRequest
     * @return
     */
    @Override
    public ComputingRankingRequest<SHARED, ACTION> prepare(ComputingRankingRequest<SHARED, ACTION> computingRankingRequest) {
        if(this.eagerTransformation != null){
            throw new UnsupportedOperationException("Eager transformation within a dependency algorithm is not implemented yet");
        }

        Computing<RankingRequest<SHARED, ACTION>> shared = (Computing<RankingRequest<SHARED, ACTION>>)computingRankingRequest.shared();
        shared.appendComputations((Mapping<Namespace, Computation<RankingRequest<SHARED, ACTION>, Object>>)(Mapping)this.sharedMemoizedComputations, (Mapping<Namespace,Computation<RankingRequest<SHARED, ACTION>, Object>>)(Mapping)this.sharedNonMemoizedMapping);

        List<ComputingCandidate<SHARED, ACTION>> candidates = computingRankingRequest.candidates();
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> updatedDependencyMap = null;

        List<ComputingCandidate<SHARED, ACTION>> newCandidates = new ArrayList<>(candidates.size());
        for (ComputingCandidate<SHARED, ACTION> candidate : candidates) {
            candidate.getAction().appendComputations(this.actionMemoizedComputations, this.actionNonMemoizedMapping);

            if (updatedDependencyMap == null) {
                NamespacedRecord<Namespace, RankingFeatureComputationDependency> oldDependencyMap = candidate.getDependencyLookupMap();
                NamespacedRecord<Namespace, RankingFeatureComputationDependency> updated = oldDependencyMap.shallowCopy();
                Namespace[] keys = this.dependencyLookupMapping.keys();
                RankingFeatureComputationDependency[] values = this.dependencyLookupMapping.values();
                updated.putAllIfAbsent(keys, values);
                updatedDependencyMap = updated;
            }

            newCandidates.add(
                    candidate.appendComputations(
                            this.interactionMemoizedComputations,
                            this.interactionNonMemoizedMapping,
                            updatedDependencyMap
                    )
            );
        }

        return new ComputingRankingRequest<>(
                computingRankingRequest.rankingRequest(),
                shared,
                newCandidates
        );
    }

    private ComputingRankingRequest<SHARED, ACTION> prepareInternal(
            NamespacedRecord<Namespace, Holder<Object>> precomputed,
            RankingRequest<SHARED, ACTION> rankingRequest,
            Computable<RankingRequest<SHARED, ACTION>> computingShared,
            List<Computable<ACTION>> computingActions
    ) {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> updatedDependencyMap = this.dependencyLookupMap.shallowCopy();
        for (Namespace namespace : precomputed.asMap().keySet()) {
            updatedDependencyMap.put(namespace, RankingFeatureComputationDependency.SHARED);
        }

        List<ComputingCandidate<SHARED, ACTION>> computingCandidates = ListTransform.map(
                computingActions,
                computingAction -> new ComputingCandidate<>(
                        computingShared,
                        computingAction,
                        updatedDependencyMap,
                        this.interactionMemoizedComputations,
                        this.interactionNonMemoizedComputations
                )
        );
        return new ComputingRankingRequest<>(
                rankingRequest,
                computingShared,
                computingCandidates
        );
    }

    @Override
    public List<TransformationMetadata> getTransformationMetadata() {
        List<TransformationMetadata> ret = new ArrayList<>();
        for (Namespace namespace : sharedMemoizedComputations.asMap().keySet()) {
            Computation computation = sharedMemoizedComputations.asMap().get(namespace);
            boolean isEnabledAsFeature = usedFeatures.contains(namespace);
            boolean isCacheEnabled = true;
            ret.add(getTransformationMetadata(RankingFeatureComputationDependency.SHARED, namespace, computation, isEnabledAsFeature, isCacheEnabled));
        }
        for (Namespace namespace : sharedNonMemoizedComputations.asMap().keySet()) {
            Computation computation = sharedNonMemoizedComputations.get(namespace);
            boolean isEnabledAsFeature = usedFeatures.contains(namespace);
            boolean isCacheEnabled = false;
            ret.add(getTransformationMetadata(RankingFeatureComputationDependency.SHARED, namespace, computation, isEnabledAsFeature, isCacheEnabled));
        }
        for (Namespace namespace : precomputedShared.asMap().keySet()) {
            Object computation = precomputedShared.get(namespace);
            boolean isEnabledAsFeature = usedFeatures.contains(namespace);
            boolean isCacheEnabled = false;
            ret.add(new TransformationMetadata(
                    namespace,
                    Arrays.asList(namespace.getComponents()),
                    RankingFeatureComputationDependency.SHARED,
                    "<precomputed>",
                    computation.getClass(),
                    isEnabledAsFeature,
                    isCacheEnabled
            ));
        }
        for (Namespace namespace : actionMemoizedComputations.asMap().keySet()) {
            Computation computation = actionMemoizedComputations.asMap().get(namespace);
            boolean isEnabledAsFeature = usedFeatures.contains(namespace);
            boolean isCacheEnabled = true;
            ret.add(getTransformationMetadata(RankingFeatureComputationDependency.ACTION, namespace, computation, isEnabledAsFeature, isCacheEnabled));
        }
        for (Namespace namespace : actionNonMemoizedComputations.asMap().keySet()) {
            Computation computation = actionNonMemoizedComputations.get(namespace);
            boolean isEnabledAsFeature = usedFeatures.contains(namespace);
            boolean isCacheEnabled = false;
            ret.add(getTransformationMetadata(RankingFeatureComputationDependency.ACTION, namespace, computation, isEnabledAsFeature, isCacheEnabled));
        }
        for (Namespace namespace : interactionMemoizedComputations.asMap().keySet()) {
            InteractingComputation computation = interactionMemoizedComputations.asMap().get(namespace);
            boolean isEnabledAsFeature = usedFeatures.contains(namespace);
            boolean isCacheEnabled = true;
            ret.add(getTransformationMetadata(RankingFeatureComputationDependency.INTERACTION, namespace, computation, isEnabledAsFeature, isCacheEnabled));
        }
        for (Namespace namespace : interactionNonMemoizedComputations.asMap().keySet()) {
            InteractingComputation computation = interactionNonMemoizedComputations.get(namespace);
            boolean isEnabledAsFeature = usedFeatures.contains(namespace);
            boolean isCacheEnabled = false;
            ret.add(getTransformationMetadata(RankingFeatureComputationDependency.INTERACTION, namespace, computation, isEnabledAsFeature, isCacheEnabled));
        }
        return ret;
    }

    private static <T extends Serializable> TransformationMetadata getTransformationMetadata(
            RankingFeatureComputationDependency featureComputationDependency,
            Namespace namespace,
            T computation,
            boolean isEnabledAsFeature,
            boolean isCacheEnabled
    ) {
        String methodName = resolveMethodName(computation);
        Type returnType = null;
        Class<?> returnTypeHint = namespace.getReturnTypeHint();
        if (returnTypeHint != null) {
            returnType = returnTypeHint;
        }
        List<Namespace> namespaceComponents = List.of(namespace);
        return new TransformationMetadata(
                namespace,
                namespaceComponents,
                featureComputationDependency,
                methodName,
                returnType,
                isEnabledAsFeature,
                isCacheEnabled
        );
    }

    private static String resolveMethodName(Serializable lambda) {
        try {
            SerializedLambda serializedLambda = getSerializedLambda(lambda);
            return serializedLambda.getImplClass().replace('/', '.') + "::" + serializedLambda.getImplMethodName();
        } catch (Exception e) {
        }
        return "Unknown";
    }

    private static SerializedLambda getSerializedLambda(Serializable lambda) throws Exception {
        Method writeReplaceMethod = lambda.getClass().getDeclaredMethod("writeReplace");
        writeReplaceMethod.setAccessible(true);
        Object serializedForm = writeReplaceMethod.invoke(lambda);
        if (serializedForm instanceof SerializedLambda) {
            return (SerializedLambda) serializedForm;
        } else {
            throw new IllegalArgumentException("The provided lambda is not a SerializedLambda");
        }
    }
}
