package com.hotvect.core.combine;

import com.hotvect.api.audit.RawFeatureName;
import com.hotvect.api.data.*;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.core.audit.AuditableCombiner;
import com.hotvect.core.audit.HashedFeatureName;
import com.hotvect.core.hash.HashUtils;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.hotvect.core.hash.HashUtils.FNV1_PRIME_32;

/**
 * A {@link Combiner} that creates specified feature interactions
 *
 */
public class InteractionCombiner<FEATURE extends FeatureNamespace> implements AuditableCombiner<FEATURE> {
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
//    private ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue;
//    private ThreadLocal<Map<Integer, List<RawFeatureName>>> featureHash2SourceRawValue;

    // Parameters for combinations
    private final int bitMask;
    private final FeatureDefinition[] featureDefinitions;

    @Override
    public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit(ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue) {
//        checkNotNull(featureName2SourceRawValue, "featureName2SourceRawValue");
//        this.featureName2SourceRawValue = featureName2SourceRawValue;
//        this.featureHash2SourceRawValue = ThreadLocal.withInitial(HashMap::new);
//        return this.featureHash2SourceRawValue;
        return null;
    }

    @Override
    public void clearAuditState() {
//        if(this.featureHash2SourceRawValue != null){
//            this.featureHash2SourceRawValue.get().clear();
//        }
    }


    /**
     * Construct a {@link InteractionCombiner}
     *
     * @param bits               number of bits to use for the feature hashes. Must be equal or smaller than 32
     * @param featureDefinitions definition of features, which may include feature interactions
     */
    public InteractionCombiner(int bits, Set<FeatureDefinition> featureDefinitions) {
        checkArgument(bits <= 32);
        if (bits == 32) {
            this.bitMask = -1;
        } else {
            BigInteger bitMask = BigInteger.valueOf(2).pow(bits).subtract(BigInteger.ONE);
            this.bitMask = bitMask.intValueExact();
        }

        this.featureDefinitions = featureDefinitions.toArray(new FeatureDefinition[0]);
    }

    /**
     * Construct a feature vector from the specified record
     *
     * @param input the input {@link com.hotvect.api.data.DataRecord} to construct the feature vector with
     * @return the constructed feature vector
     */
    @Override
    public SparseVector apply(NamespacedRecord<FEATURE, HashedValue> input) {
        CacheEntry cached = CACHE.get();
        IntOpenHashSet categorical = cached.categorical;
        Int2DoubleOpenHashMap numerical = cached.numerical;

        // Add constant feature
        categorical.add(0);

        for (FeatureDefinition fd : featureDefinitions) {

            if (fd.getValueType() == HashedValueType.CATEGORICAL) {
                // Categorical
                construct(this.bitMask, fd, categorical, input);
            } else {
                // Numerical
                FeatureNamespace element = fd.getComponents()[0];
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

//                    if (featureHash2SourceRawValue != null) {
//                        // Audit enabled
//                        HashedFeatureName hashedFeatureName = new HashedFeatureName(element, featureName);
//                        RawFeatureName sourceRawData = this.featureName2SourceRawValue.get().get(hashedFeatureName);
//                        this.featureHash2SourceRawValue.get().put(featureHash, ImmutableList.of(sourceRawData));
//                    }
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
     * Given a {@link com.hotvect.api.data.DataRecord}, add the specified interactions to the accumulating {@link IntCollection}
     *
     * @param mask              Bitmask to be used for feature hashing
     * @param featureDefinition Definition of features, which may include interaction features
     * @param acc               Accumulator to which feaure hashes will be added
     * @param record            Input hashed {@link com.hotvect.api.data.DataRecord}
     */
    private void construct(int mask,
                           FeatureDefinition featureDefinition,
                           IntCollection acc,
                           NamespacedRecord<FEATURE, HashedValue> record) {
        FeatureNamespace[] toInteract = featureDefinition.getComponents();
        if (toInteract.length == 1) {
            // There is only one component
            FeatureNamespace featureNamespace = toInteract[0];
            HashedValue value = record.get(featureNamespace);
            if (value != null) {
                for (int el : value.getCategoricalIndices()) {
                    int hash = (featureDefinition.getFeatureNamespace() * FNV1_PRIME_32) ^ HashUtils.hashInt(el);
                    int finalHash = hash & mask;
                    acc.add(finalHash);

//                    if(this.featureHash2SourceRawValue != null){
//                        // Audit enabled
//                        HashedFeatureName hashedFeatureName = new HashedFeatureName(featureNamespace, el);
//                        RawFeatureName sourceRawData = this.featureName2SourceRawValue.get().get(hashedFeatureName);
//                        this.featureHash2SourceRawValue.get().put(finalHash, ImmutableList.of(sourceRawData));
//                    }

                }
            }
        } else {
            // There are more than one component - it is an interaction feature
            interact(mask, featureDefinition, acc, record);
        }
    }

    private void interact(int mask,
                          FeatureDefinition fd,
                          IntCollection acc,
                          NamespacedRecord<FEATURE, HashedValue> values) {
        FeatureNamespace[] toInteract = fd.getComponents();

        // First, we calculate how many results we would be getting
        int solutions = 1;
        for (FeatureNamespace feature : toInteract) {
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


            List<RawFeatureName> rawFeatureNames = new ArrayList<>();
            for (FeatureNamespace namespace : toInteract) {
                int[] featureNames = values.get(namespace).getCategoricalIndices();
                int featureName = featureNames[(i / j) % featureNames.length];
                hash ^= HashUtils.hashInt(featureName);
                hash *= FNV1_PRIME_32;


//                if(this.featureHash2SourceRawValue != null){
//                    HashedFeatureName hashedFeatureName = new HashedFeatureName(namespace, featureName);
//                    RawFeatureName sourceRawData = this.featureName2SourceRawValue.get().get(hashedFeatureName);
//                    rawFeatureNames.add(sourceRawData);
//                }


                j *= featureNames.length;
            }
            int finalHash = hash & mask;
            acc.add(finalHash);

//            if(this.featureHash2SourceRawValue != null){
//                this.featureHash2SourceRawValue.get().put(finalHash, ImmutableList.copyOf(rawFeatureNames));
//            }

        }
    }


}

