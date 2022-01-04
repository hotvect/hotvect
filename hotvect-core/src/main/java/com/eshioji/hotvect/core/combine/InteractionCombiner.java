package com.eshioji.hotvect.core.combine;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.hashed.HashedValueType;
import com.eshioji.hotvect.core.audit.AuditableCombiner;
import com.eshioji.hotvect.core.audit.HashedFeatureName;
import com.eshioji.hotvect.core.audit.RawFeatureName;
import com.eshioji.hotvect.core.hash.HashUtils;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.*;

import static com.eshioji.hotvect.core.hash.HashUtils.FNV1_PRIME_32;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link Combiner} that creates specified feature interactions
 *
 * @param <H> the {@link FeatureNamespace} to be used
 */
public class InteractionCombiner<H extends Enum<H> & FeatureNamespace> implements AuditableCombiner<H> {
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
    private final FeatureDefinition<H>[] featureDefinitions;

    @Override
    public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit(ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue) {
        checkNotNull(featureName2SourceRawValue, "featureName2SourceRawValue");
        this.featureName2SourceRawValue = featureName2SourceRawValue;
        this.featureHash2SourceRawValue = ThreadLocal.withInitial(HashMap::new);
        return this.featureHash2SourceRawValue;
    }


    /**
     * Construct a {@link InteractionCombiner}
     *
     * @param bits               number of bits to use for the feature hashes. Must be equal or smaller than 32
     * @param featureDefinitions definition of features, which may include feature interactions
     */
    public InteractionCombiner(int bits, Set<FeatureDefinition<H>> featureDefinitions) {
        checkArgument(bits <= 32);
        if (bits == 32) {
            this.bitMask = -1;
        } else {
            BigInteger bitMask = BigInteger.valueOf(2).pow(bits).subtract(BigInteger.ONE);
            this.bitMask = bitMask.intValueExact();
        }

        @SuppressWarnings("unchecked")
        FeatureDefinition<H>[] definitions = (FeatureDefinition<H>[]) Array.newInstance(FeatureDefinition.class, featureDefinitions.size());
        this.featureDefinitions = featureDefinitions.toArray(definitions);
    }

    /**
     * Construct a feature vector from the specified record
     *
     * @param input the input {@link DataRecord} to construct the feature vector with
     * @return the constructed feature vector
     */
    @Override
    public SparseVector apply(DataRecord<H, HashedValue> input) {
        CacheEntry cached = CACHE.get();
        IntOpenHashSet categorical = cached.categorical;
        Int2DoubleOpenHashMap numerical = cached.numerical;

        // Add constant feature
        categorical.add(0);

        for (FeatureDefinition<H> fd : featureDefinitions) {

            if (fd.getValueType() == HashedValueType.CATEGORICAL) {
                // Categorical
                construct(this.bitMask, fd, categorical, input);
            } else {
                // Numerical
                H element = fd.getComponents()[0];
                HashedValue data = input.get(element);
                if (data == null) {
                    continue; // Missing
                }
                int[] featureNames = data.getCategoricalIndices();
                double[] featureValues = data.getNumericals();

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

        // Create the arrays for the final feature vector
        int size = categorical.size() + numerical.size();
        int[] idx = new int[size];
        double[] vals = new double[size];

        // Add categorical indexes
        categorical.toArray(idx);
        // Fill 1.0 for categorical values
        Arrays.fill(vals, 0, categorical.size(), 1.0);

        if (!numerical.isEmpty()) {
            int[] numericalIdx = numerical.keySet().toIntArray();
            double[] numericalVals = new double[numericalIdx.length];
            for (int i = 0; i < numericalIdx.length; i++) {
                int ni = numericalIdx[i];
                numericalVals[i] = numerical.get(ni);
            }

            // Copy the numerical indexes/values to the final array after the categorical values
            System.arraycopy(numericalIdx, 0, idx, categorical.size(), numericalIdx.length);
            System.arraycopy(numericalVals, 0, vals, categorical.size(), numericalVals.length);
        }
        return new SparseVector(idx, vals);
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
                           FeatureDefinition<H> featureDefinition,
                           IntCollection acc,
                           DataRecord<H, HashedValue> record) {
        H[] toInteract = featureDefinition.getComponents();
        if (toInteract.length == 1) {
            // There is only one component
            H featureNamespace = toInteract[0];
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
                          FeatureDefinition<H> fd,
                          IntCollection acc,
                          DataRecord<H, HashedValue> values) {
        H[] toInteract = fd.getComponents();

        // First, we calculate how many results we would be getting
        int solutions = 1;
        for (H h : toInteract) {
            HashedValue data = values.get(h);
            if (data == null) {
                // If any of the elements for interaction is not available, abort
                return;
            }
            solutions *= data.getCategoricalIndices().length;
        }

        for (int i = 0; i < solutions; i++) {
            int j = 1;
            int hash = fd.getFeatureNamespace();



            for (H namespace : toInteract) {
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
                for (H namespace : toInteract) {
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

