package com.hotvect.core.combine;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.HashedValueType;
import com.hotvect.core.audit.AuditableCombiner;
import com.hotvect.core.audit.HashedFeatureName;
import com.hotvect.core.audit.RawFeatureName;
import com.hotvect.core.hash.HashUtils;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.*;

import static com.hotvect.core.hash.HashUtils.FNV1_PRIME_32;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link Combiner} that creates specified feature interactions
 *
 * @param <FEATURE> the {@link FeatureNamespace} to be used
 */
public class InteractionCombiner<FEATURE extends Enum<FEATURE> & FeatureNamespace> implements AuditableCombiner<FEATURE> {
    private final ThreadLocal<CacheEntry> CACHE = new ThreadLocal<>() {
        @Override
        protected CacheEntry initialValue() {
            return new CacheEntry();
        }

        @Override
        public CacheEntry get() {
            CacheEntry cached = super.get();
            cached.categorical.clear();
            cached.numerical.clear();
            return cached;
        }
    };

    private static class CacheEntry {
        final IntOpenHashSet categorical = new IntOpenHashSet();
        final Int2DoubleOpenHashMap numerical = new Int2DoubleOpenHashMap();
    }


    // Auditing
    private ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue;
    private ThreadLocal<Map<Integer, List<RawFeatureName>>> featureHash2SourceRawValue;

    // Parameters for combinations
    private final int bitMask;
    private final FeatureDefinition<FEATURE>[] featureDefinitions;

    @Override
    public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit(ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue) {
        checkNotNull(featureName2SourceRawValue, "featureName2SourceRawValue");
        this.featureName2SourceRawValue = featureName2SourceRawValue;
        this.featureHash2SourceRawValue = ThreadLocal.withInitial(HashMap::new);
        return this.featureHash2SourceRawValue;
    }

    @Override
    public void clearAuditState() {
        if(this.featureHash2SourceRawValue != null){
            this.featureHash2SourceRawValue.get().clear();
        }
    }


    /**
     * Construct a {@link InteractionCombiner}
     *
     * @param bits               number of bits to use for the feature hashes. Must be equal or smaller than 32
     * @param featureDefinitions definition of features, which may include feature interactions
     */
    public InteractionCombiner(int bits, Set<FeatureDefinition<FEATURE>> featureDefinitions) {
        checkArgument(bits <= 32);
        if (bits == 32) {
            this.bitMask = -1;
        } else {
            BigInteger bitMask = BigInteger.valueOf(2).pow(bits).subtract(BigInteger.ONE);
            this.bitMask = bitMask.intValueExact();
        }

        @SuppressWarnings("unchecked")
        FeatureDefinition<FEATURE>[] definitions = (FeatureDefinition<FEATURE>[]) Array.newInstance(FeatureDefinition.class, featureDefinitions.size());
        this.featureDefinitions = featureDefinitions.toArray(definitions);
    }

    /**
     * Construct a feature vector from the specified record
     *
     * @param input the input {@link DataRecord} to construct the feature vector with
     * @return the constructed feature vector
     */
    @Override
    public SparseVector apply(DataRecord<FEATURE, HashedValue> input) {
        CacheEntry cached = CACHE.get();
        IntOpenHashSet categorical = cached.categorical;
        Int2DoubleOpenHashMap numerical = cached.numerical;

        // Add constant feature
        categorical.add(0);

        for (FeatureDefinition<FEATURE> fd : featureDefinitions) {

            if (fd.getValueType() == HashedValueType.CATEGORICAL) {
                // Categorical
                construct(this.bitMask, fd, categorical, input);
            } else {
                // Numerical
                FEATURE element = fd.getComponents()[0];
                HashedValue data = input.get(element);
                if (data == null) {
                    continue; // Missing
                }
                int[] featureNames = data.getNumericalIndices();
                double[] featureValues = data.getNumericalValues();

                for (int i = 0; i < featureNames.length; i++) {
                    int featureName = featureNames[i];
                    int featureHash = HashUtils.namespace(this.bitMask, fd, featureName);
                    numerical.put(featureHash, featureValues[i]);

                    if (featureHash2SourceRawValue != null) {
                        // Audit enabled
                        HashedFeatureName hashedFeatureName = new HashedFeatureName(element, featureName);
                        RawFeatureName sourceRawData = this.featureName2SourceRawValue.get().get(hashedFeatureName);
                        this.featureHash2SourceRawValue.get().put(featureHash, ImmutableList.of(sourceRawData));
                    }
                }

            }
        }

        int[] categoricalIndices = categorical.toIntArray();
        if(!numerical.isEmpty()){
            int[] numericalIndices = numerical.keySet().toIntArray();
            double[] numericalValues = new double[numericalIndices.length];
            for (int i = 0; i < numericalIndices.length; i++) {
                int ni = numericalIndices[i];
                numericalValues[i] = numerical.get(ni);
            }
            return new SparseVector(categoricalIndices, numericalIndices, numericalValues);
        } else {
            return new SparseVector(categoricalIndices);
        }
    }


    /**
     * Given a {@link DataRecord}, add the specified interactions to the accumulating {@link IntCollection}
     *
     * @param mask              Bitmask to be used for feature hashing
     * @param featureDefinition Definition of features, which may include interaction features
     * @param acc               Accumulator to which feaure hashes will be added
     * @param record            Input hashed {@link DataRecord}
     */
    private void construct(int mask,
                           FeatureDefinition<FEATURE> featureDefinition,
                           IntCollection acc,
                           DataRecord<FEATURE, HashedValue> record) {
        FEATURE[] toInteract = featureDefinition.getComponents();
        if (toInteract.length == 1) {
            // There is only one component
            FEATURE featureNamespace = toInteract[0];
            HashedValue value = record.get(featureNamespace);
            if (value != null) {
                for (int el : value.getCategoricalIndices()) {
                    int hash = (featureDefinition.getFeatureNamespace() * FNV1_PRIME_32) ^ HashUtils.hashInt(el);
                    int finalHash = hash & mask;
                    acc.add(finalHash);

                    if(this.featureHash2SourceRawValue != null){
                        // Audit enabled
                        HashedFeatureName hashedFeatureName = new HashedFeatureName(featureNamespace, el);
                        RawFeatureName sourceRawData = this.featureName2SourceRawValue.get().get(hashedFeatureName);
                        this.featureHash2SourceRawValue.get().put(finalHash, ImmutableList.of(sourceRawData));
                    }

                }
            }
        } else {
            // There are more than one component - it is an interaction feature
            interact(mask, featureDefinition, acc, record);
        }
    }

    private void interact(int mask,
                          FeatureDefinition<FEATURE> fd,
                          IntCollection acc,
                          DataRecord<FEATURE, HashedValue> values) {
        FEATURE[] toInteract = fd.getComponents();

        // First, we calculate how many results we would be getting
        int solutions = 1;
        for (FEATURE feature : toInteract) {
            HashedValue data = values.get(feature);
            if (data == null) {
                // If any of the elements for interaction is not available, abort
                return;
            }
            solutions *= data.getCategoricalIndices().length;
        }

        for (int i = 0; i < solutions; i++) {
            int j = 1;
            int hash = fd.getFeatureNamespace();



            for (FEATURE namespace : toInteract) {
                int[] featureNames = values.get(namespace).getCategoricalIndices();
                int featureName = featureNames[(i / j) % featureNames.length];
                hash ^= HashUtils.hashInt(featureName);
                hash *= FNV1_PRIME_32;
                j *= featureNames.length;
            }
            int finalHash = hash & mask;
            acc.add(finalHash);

            if(this.featureHash2SourceRawValue != null){
                // Audit enabled
                List<RawFeatureName> rawFeatureNames = new ArrayList<>();
                for (FEATURE namespace : toInteract) {
                    int[] featureNames = values.get(namespace).getCategoricalIndices();
                    int featureName = featureNames[(i / j) % featureNames.length];

                    HashedFeatureName hashedFeatureName = new HashedFeatureName(namespace, featureName);
                    RawFeatureName sourceRawData = this.featureName2SourceRawValue.get().get(hashedFeatureName);
                    rawFeatureNames.add(sourceRawData);
                    j *= featureNames.length;
                }

                this.featureHash2SourceRawValue.get().put(finalHash, ImmutableList.copyOf(rawFeatureNames));
            }

        }
    }


}

