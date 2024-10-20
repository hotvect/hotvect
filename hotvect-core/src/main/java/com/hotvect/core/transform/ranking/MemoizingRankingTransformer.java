package com.hotvect.core.transform.ranking;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.hotvect.api.data.*;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.RankingFeatureComputationDependency;
import com.hotvect.api.transformation.memoization.*;
import com.hotvect.api.transformation.ranking.MemoizableBulkScorer;
import com.hotvect.api.transformation.ranking.MemoizableRankingTransformer;
import com.hotvect.api.transformation.ranking.MemoizedRankingRequest;
import com.hotvect.utils.FuzzyMatch;
import com.hotvect.utils.ListTransform;
import com.hotvect.utils.Utils;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.jodah.typetools.TypeResolver;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "rawtypes"})
public class MemoizingRankingTransformer<SHARED, ACTION> implements MemoizableRankingTransformer<SHARED, ACTION> {
    private final Mapping<Namespace, Computation<SHARED, Object>> sharedMemoizedComputations;
    private final Mapping<Namespace, Computation<ACTION, Object>> actionMemoizedComputations;
    private final Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionMemoizedComputations;

    private final NamespacedRecord<Namespace, Computation<SHARED, Object>> sharedNonMemoizedComputations;
    private final NamespacedRecord<Namespace, Computation<ACTION, Object>> actionNonMemoizedComputations;
    private final NamespacedRecord<Namespace, InteractingComputation<SHARED, ACTION, Object>> interactionNonMemoizedComputations;

    private final NamespacedRecord<FeatureNamespace, MemoizableBulkScorer<SHARED, ACTION>> bulkScorers;
    private final EnumMap<RankingFeatureComputationDependency, FeatureNamespace[]> computationFeatures;
    private final NamespacedRecord<Namespace, RankingFeatureComputationDependency> dependencyLookupMap;
    private final FeatureNamespace[] algorithmFeatures;
    private final SortedSet<FeatureNamespace> usedFeatures;

    public static class Builder<SHARED, ACTION> {
        private final Map<String, Computation> sharedComputations = new HashMap<>();
        private final Map<String, Computation> actionComputations = new HashMap<>();
        private final Map<String, InteractingComputation> interactionComputations = new HashMap<>();
        private final Map<String, MemoizableBulkScorer<SHARED, ACTION>> bulkScorers = new HashMap<>();
        private final Set<String> cachedNamespaces = new HashSet<>();
        private final Map<String, Namespace> namespaceDictionary = new HashMap<>();
        private final Set<String> usedFeatures = new HashSet<>();

        private Builder() {
        }

        public <V> Builder<SHARED, ACTION> withSharedComputation(Namespace namespace, Computation<SHARED, V> computation) {
            this.sharedComputations.put(namespace.toString(), computation);
            this.cachedNamespaces.add(namespace.toString());
            this.namespaceDictionary.put(namespace.toString(), namespace);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withActionComputation(Namespace namespace, Computation<ACTION, V> computation) {
            String namespaceString = namespace.toString();
            this.actionComputations.put(namespaceString, computation);
            this.namespaceDictionary.put(namespaceString, namespace);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withInteractionComputation(Namespace namespace, InteractingComputation<SHARED, ACTION, V> computation) {
            this.interactionComputations.put(namespace.toString(), computation);
            this.namespaceDictionary.put(namespace.toString(), namespace);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withSharedComputation(Namespace namespace, Computation<SHARED, V> computation, boolean cached) {
            String namespaceString = namespace.toString();
            this.sharedComputations.put(namespaceString, computation);
            if (cached) {
                this.cachedNamespaces.add(namespaceString);
            }
            this.namespaceDictionary.put(namespaceString, namespace);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withActionComputation(Namespace namespace, Computation<ACTION, V> computation, boolean cached) {
            String namespaceString = namespace.toString();
            this.actionComputations.put(namespaceString, computation);
            if (cached) {
                this.cachedNamespaces.add(namespaceString);
            }
            this.namespaceDictionary.put(namespaceString, namespace);
            return this;
        }

        public <V> Builder<SHARED, ACTION> withInteractionComputation(Namespace namespace, InteractingComputation<SHARED, ACTION, V> computation, boolean cached) {
            String namespaceString = namespace.toString();
            this.interactionComputations.put(namespaceString, computation);
            if (cached) {
                this.cachedNamespaces.add(namespaceString);
            }
            this.namespaceDictionary.put(namespaceString, namespace);
            return this;
        }

        public Builder<SHARED, ACTION> withBulkScorer(FeatureNamespace namespace, MemoizableBulkScorer<SHARED, ACTION> bulkScorer) {
            this.bulkScorers.put(namespace.toString(), bulkScorer);
            this.namespaceDictionary.put(namespace.toString(), namespace);
            return this;
        }

        public Builder<SHARED, ACTION> withFeature(String namespace) {
            this.usedFeatures.add(namespace);
            return this;
        }

        public Builder<SHARED, ACTION> enableCachingFor(String namespace) {
            Namespace cachedNamespace = findNamespace(namespace);
            this.cachedNamespaces.add(cachedNamespace.toString());
            return this;
        }

        public Builder<SHARED, ACTION> enableCachingFor(Namespace namespace) {
            this.cachedNamespaces.add(namespace.toString());
            return this;
        }

        private Namespace findNamespace(String namespace) {
            if (this.namespaceDictionary.containsKey(namespace)) {
                return this.namespaceDictionary.get(namespace);
            } else {
                throw generateNamespaceNotFoundError(namespace);
            }
        }

        private FeatureNamespace findFeatureNamespace(String namespace) {
            Namespace ns = findNamespace(namespace);
            checkArgument(FeatureNamespace.class.isAssignableFrom(ns.getClass()), "Namespace: %s is not a ranking feature namespace. To use as feature, have it implement the interface %s", namespace, FeatureNamespace.class.getSimpleName());
            return (FeatureNamespace) ns;
        }

        public MemoizingRankingTransformer<SHARED, ACTION> build() {
            Map<FeatureNamespace, MemoizableBulkScorer<SHARED, ACTION>> bulkScorer = asNamespaceMap(bulkScorers);
            Set<Namespace> internedUsedFeatures = asNamespaceSet(usedFeatures);
            SortedSet<FeatureNamespace> sortedUsedFeatures = new TreeSet<>(FeatureNamespace.alphabetical());
            for (Namespace usedFeature : internedUsedFeatures) {
                if (!FeatureNamespace.class.isAssignableFrom(usedFeature.getClass())) {
                    throw new IllegalArgumentException("Namespace: " + usedFeature + " is not a feature namespace. To use as feature, have it implement the interface " + FeatureNamespace.class.getSimpleName());
                }
                sortedUsedFeatures.add((FeatureNamespace) usedFeature);
            }
            return new MemoizingRankingTransformer<>(
                    asNamespaceMap(sharedComputations),
                    asNamespaceMap(actionComputations),
                    asNamespaceMap(interactionComputations),
                    bulkScorer,
                    asNamespaceSet(cachedNamespaces),
                    sortedUsedFeatures
            );
        }

        public <K, V> Map<K, V> asNamespaceMap(Map<String, V> source) {
            Map<K, V> ret = new IdentityHashMap<>();
            for (Map.Entry<String, V> entry : source.entrySet()) {
                K namespace = (K) this.namespaceDictionary.get(entry.getKey());
                checkArgument(ret.put(namespace, entry.getValue()) == null);
            }
            return ret;
        }

        public <K> Set<K> asNamespaceSet(Set<String> source) {
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

    private MemoizingRankingTransformer(
            Map<Namespace, Computation> sharedComputations,
            Map<Namespace, Computation> actionComputations,
            Map<Namespace, InteractingComputation> interactionComputations,
            Map<FeatureNamespace, MemoizableBulkScorer<SHARED, ACTION>> bulkScorers,
            Set<Namespace> cachedNamespaces,
            SortedSet<FeatureNamespace> usedFeatures
    ) {
        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(sharedComputations.keySet());
        Map<Namespace, Computation> sharedMemoizedComputations = ImmutableMap.copyOf(Maps.filterEntries(sharedComputations, e -> cachedNamespaces.contains(e.getKey())));
        this.sharedMemoizedComputations = (Mapping<Namespace, Computation<SHARED, Object>>)(Mapping<?, ?>) new Mapping<>(
                sharedMemoizedComputations, Namespace.class, Computation.class);
        Map sharedNonMemoizedComputations = ImmutableMap.copyOf(Maps.filterEntries(sharedComputations, x -> !cachedNamespaces.contains(x.getKey())));
        this.sharedNonMemoizedComputations = new NamespacedRecordImpl<>(
                sharedNonMemoizedComputations);

        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(actionComputations.keySet());
        Map actionMemoizedComputations = ImmutableMap.copyOf(Maps.filterEntries(actionComputations, e -> cachedNamespaces.contains(e.getKey())));
        this.actionMemoizedComputations = (Mapping<Namespace, Computation<ACTION, Object>>)(Mapping<?, ?>) new Mapping<>(
                actionMemoizedComputations, Namespace.class, Computation.class);
        Map actionNonMemoizedComputations = ImmutableMap.copyOf(Maps.filterEntries(actionComputations, x -> !cachedNamespaces.contains(x.getKey())));
        this.actionNonMemoizedComputations = new NamespacedRecordImpl<>(
                actionNonMemoizedComputations);

        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(interactionComputations.keySet());
        Map interactionMemoizedComputations = ImmutableMap.copyOf(Maps.filterEntries(interactionComputations, e -> cachedNamespaces.contains(e.getKey())));
        this.interactionMemoizedComputations = (Mapping<Namespace, InteractingComputation<SHARED, ACTION, Object>>)(Mapping<?, ?>) new Mapping<>(
                interactionMemoizedComputations, Namespace.class, InteractingComputation.class);
        Map interactionNonMemoizedComputations = ImmutableMap.copyOf(Maps.filterEntries(interactionComputations, x -> !cachedNamespaces.contains(x.getKey())));
        this.interactionNonMemoizedComputations = new NamespacedRecordImpl<>(
                interactionNonMemoizedComputations);

        Utils.checkCollectionIsEnumsOrNamespaceIdObjects(bulkScorers.keySet());

        this.bulkScorers = new NamespacedRecordImpl<>(bulkScorers);
        this.usedFeatures = ImmutableSortedSet.copyOf(FeatureNamespace.alphabetical(), usedFeatures);
        this.dependencyLookupMap = toDependencyLookupMap(
                sharedComputations,
                actionComputations,
                interactionComputations,
                bulkScorers
        );

        EnumMap<RankingFeatureComputationDependency, SortedSet<FeatureNamespace>> computationFeatures = new EnumMap<>(RankingFeatureComputationDependency.class);
        SortedSet<FeatureNamespace> algorithmFeatures = new TreeSet<>(FeatureNamespace.alphabetical());

        for (FeatureNamespace usedFeature : this.usedFeatures) {
            RankingFeatureComputationDependency dependency = checkNotNull(dependencyLookupMap.get(usedFeature));
            SortedSet<FeatureNamespace> featureSet;
            if (dependency == RankingFeatureComputationDependency.STACKING) {
                // Dependency = stacking means algorithm feature
                featureSet = algorithmFeatures;
            } else {
                featureSet = computationFeatures.computeIfAbsent(dependency, x -> new TreeSet<>(FeatureNamespace.alphabetical()));
            }
            featureSet.add(usedFeature);
        }

        if (computationFeatures.isEmpty()) {
            this.computationFeatures = null;
        } else {
            this.computationFeatures = new EnumMap<>(Maps.transformValues(
                    computationFeatures,
                    v -> v.toArray(new FeatureNamespace[0])
            ));
        }
        this.algorithmFeatures = algorithmFeatures.toArray(new FeatureNamespace[0]);
        if ((this.computationFeatures == null || this.computationFeatures.isEmpty()) && this.algorithmFeatures.length == 0) {
            throw new WrongTransformationDefinitionException("No features were registered for this transformer. At least one feature must be registered.");
        }
    }

    private NamespacedRecord<Namespace, RankingFeatureComputationDependency> toDependencyLookupMap(
            Map<Namespace, Computation> sharedComputations,
            Map<Namespace, Computation> actionComputations,
            Map<Namespace, InteractingComputation> interactionComputations,
            Map<FeatureNamespace, ?> bulkScorers
    ) {
        NamespacedRecord<Namespace, RankingFeatureComputationDependency> ret = new NamespacedRecordImpl<>();
        sharedComputations.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.SHARED));
        actionComputations.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.ACTION));
        interactionComputations.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.INTERACTION));
        bulkScorers.keySet().forEach(namespace -> ret.put(namespace, RankingFeatureComputationDependency.STACKING));
        return ret;
    }

    @Override
    public List<NamespacedRecord<FeatureNamespace, RawValue>> apply(MemoizedRankingRequest<SHARED, ACTION> input) {
        List<NamespacedRecord<FeatureNamespace, RawValue>> ret = new ArrayList<>(input.getAction().size());

        // Non-bulk computations
        for (ComputingCandidate<SHARED, ACTION> candidate : input.getAction()) {
            NamespacedRecord<FeatureNamespace, RawValue> transformed = new NamespacedRecordImpl<>();
            if (this.computationFeatures != null) {
                FeatureNamespace[] sharedFeatures = this.computationFeatures.get(RankingFeatureComputationDependency.SHARED);
                if (sharedFeatures != null) {
                    for (FeatureNamespace feature : sharedFeatures) {
                        Object computationResult = candidate.getShared().computeIfAbsent(feature);
                        checkAndAdd(transformed, computationResult, feature);
                    }
                }
                FeatureNamespace[] actionFeatures = this.computationFeatures.get(RankingFeatureComputationDependency.ACTION);
                if (actionFeatures != null) {
                    for (FeatureNamespace actionFeature : actionFeatures) {
                        Object computationResult = candidate.getAction().computeIfAbsent(actionFeature);
                        checkAndAdd(transformed, computationResult, actionFeature);
                    }
                }
                FeatureNamespace[] interactionFeatures = this.computationFeatures.get(RankingFeatureComputationDependency.INTERACTION);
                if (interactionFeatures != null) {
                    for (FeatureNamespace feature : interactionFeatures) {
                        Object computationResult = candidate.computeIfAbsent(feature);
                        checkAndAdd(transformed, computationResult, feature);
                    }
                }
            }
            ret.add(transformed);
        }

        // Bulk scores
        for (FeatureNamespace featureNamespace : this.algorithmFeatures) {
            MemoizableBulkScorer<SHARED, ACTION> bulkScorer = this.bulkScorers.get(featureNamespace);
            DoubleList scores = bulkScorer.apply(input);
            for (int i = 0; i < ret.size(); i++) {
                NamespacedRecord<FeatureNamespace, RawValue> record = ret.get(i);
                double score = scores.getDouble(i);
                record.put(featureNamespace, RawValue.singleNumerical(score));
            }
        }

        return ret;
    }

    private void checkAndAdd(NamespacedRecord<FeatureNamespace, RawValue> transformed, Object computationResult, FeatureNamespace feature) {
        if (computationResult == null) {
            return;
        }
        if (computationResult instanceof RawValue) {
            RawValue v = (RawValue) computationResult;
            transformed.put(feature, v);
        } else if (computationResult instanceof String) {
            transformed.put(feature, RawValue.singleString((String) computationResult));
        } else if (computationResult instanceof Double) {
            transformed.put(feature, RawValue.singleNumerical((Double) computationResult));
        } else if (computationResult instanceof String[]) {
            transformed.put(feature, RawValue.strings((String[]) computationResult));
        } else if (computationResult instanceof Integer) {
            transformed.put(feature, RawValue.singleCategorical((Integer) computationResult));
        } else {
            throw new IllegalStateException(
                    "Computation defined for feature:" + feature + " returned an object of type " + computationResult.getClass() +
                            " that is neither of " + List.of(RawValue.class, String.class, Double.class, String[].class) + ". " +
                            "For performance reasons, computations that are used for features must return one of these types."
            );
        }
    }

    @Override
    public SortedSet<FeatureNamespace> getUsedFeatures() {
        return usedFeatures;
    }

    @Override
    public MemoizedRankingRequest<SHARED, ACTION> memoize(String exampleId, SHARED shared, List<Computing<ACTION>> actions) {
        Computing<SHARED> computingShared = Computing.withComputations(
                shared,
                sharedMemoizedComputations,
                sharedNonMemoizedComputations
        );
        RankingRequest<SHARED, ACTION> rankingRequest = new RankingRequest<>(exampleId, shared, ListTransform.map(actions, Computing::getOriginalInput));
        return new MemoizedRankingRequest<>(
                rankingRequest,
                computingShared,
                ListTransform.map(
                        actions,
                        a -> {
                            a.appendComputations(actionMemoizedComputations, actionNonMemoizedComputations);
                            return ComputingCandidate.withComputations(
                                    dependencyLookupMap,
                                    computingShared,
                                    a,
                                    interactionMemoizedComputations,
                                    interactionNonMemoizedComputations
                            );
                        }
                )
        );
    }

    @Override
    public MemoizedRankingRequest<SHARED, ACTION> memoize(RankingRequest<SHARED, ACTION> rankingRequest) {
        Computing<SHARED> computingShared = Computing.withComputations(
                rankingRequest.getShared(),
                sharedMemoizedComputations,
                sharedNonMemoizedComputations
        );
        return new MemoizedRankingRequest<>(
                rankingRequest,
                computingShared,
                ListTransform.map(
                        rankingRequest.getAvailableActions(),
                        a -> {
                            Computing<ACTION> ma = Computing.withComputations(a, actionMemoizedComputations, actionNonMemoizedComputations);
                            return ComputingCandidate.withComputations(
                                    dependencyLookupMap,
                                    computingShared,
                                    ma,
                                    interactionMemoizedComputations,
                                    interactionNonMemoizedComputations
                            );
                        }
                )
        );
    }

    @Override
    public MemoizedRankingRequest<SHARED, ACTION> memoize(MemoizedRankingRequest<SHARED, ACTION> memoizedRankingRequest) {
        throw new AssertionError();
    }

    @Beta
    public List<TransformationMetadata> getTransformationMetadata() {
        List<TransformationMetadata> ret = new ArrayList<>();
        // Shared computations
        for (Namespace namespace : sharedMemoizedComputations.keys()) {
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
        // Action computations
        for (Namespace namespace : actionMemoizedComputations.keys()) {
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
        // Interaction computations
        for (Namespace namespace : interactionMemoizedComputations.keys()) {
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

    private static <T extends Serializable> TransformationMetadata getTransformationMetadata(RankingFeatureComputationDependency featureComputationDependency,
                                                                                             Namespace namespace,
                                                                                             T computation,
                                                                                             boolean isEnabledAsFeature,
                                                                                             boolean isCacheEnabled) {
        Class<?> computationClass;
        if (computation instanceof InteractingComputation) {
            computationClass = InteractingComputation.class;
        } else if (computation instanceof Computation) {
            computationClass = Computation.class;
        } else {
            throw new IllegalArgumentException("Unknown computation type: " + computation.getClass());
        }
        Type[] types = TypeResolver.resolveRawArguments(computationClass, computation.getClass());
        String methodName = resolveMethodName(computation);
        Type returnType = (types != null && types.length == 3) ? types[2] : TypeResolver.Unknown.class;
        List<Namespace> namespaceComponents;
        if (namespace instanceof CompoundNamespace.NamespaceId) {
            CompoundNamespace.NamespaceId compoundNamespace = (CompoundNamespace.NamespaceId) namespace;
            namespaceComponents = Arrays.asList(compoundNamespace.getNamespaces());
        } else {
            namespaceComponents = List.of(namespace);
        }
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
            // ignore - shouldn't happen but could be JVM implementation dependent
        }
        return "Unknown";
    }

    private static SerializedLambda getSerializedLambda(Serializable lambda) throws Exception {
        // Obtain the writeReplace method
        Method writeReplaceMethod = lambda.getClass().getDeclaredMethod("writeReplace");
        writeReplaceMethod.setAccessible(true);
        // Invoke the writeReplace method to get the SerializedLambda object
        Object serializedForm = writeReplaceMethod.invoke(lambda);
        if (serializedForm instanceof SerializedLambda) {
            return (SerializedLambda) serializedForm;
        } else {
            throw new IllegalArgumentException("The provided lambda is not a SerializedLambda");
        }
    }
}