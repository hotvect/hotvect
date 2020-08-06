package com.eshioji.hotvect.core.combine;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.hashed.HashedNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.hashed.HashedValueType;
import com.eshioji.hotvect.core.hash.HashUtils;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A {@link Combiner} that creates specified feature interactions
 * @param <H> the {@link HashedNamespace} to be used
 */
public class InteractionCombiner<H extends Enum<H> & HashedNamespace> implements Combiner<H> {
    private static final ThreadLocal<CacheEntry> CACHE = new ThreadLocal<>() {
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


    private final int bitMask;
    private final FeatureDefinition<H>[] featureDefinitions;

    /**
     * Construct a {@link InteractionCombiner}
     * @param bits number of bits to use for the feature hashes. Must be equal or smaller than 32
     * @param featureDefinitions definition of features, which may include feature interactions
     */
    public InteractionCombiner(int bits, Set<FeatureDefinition<H>> featureDefinitions) {
        checkArgument(bits <= 32);
        if (bits == 32){
            this.bitMask = -1;
        } else {
            var bitMask = BigInteger.TWO.pow(bits).subtract(BigInteger.ONE);
            this.bitMask = bitMask.intValueExact();
        }

        @SuppressWarnings("unchecked")
        FeatureDefinition<H>[] definitions = (FeatureDefinition<H>[]) Array.newInstance(FeatureDefinition.class, featureDefinitions.size());
        this.featureDefinitions = featureDefinitions.toArray(definitions);
    }

    /**
     * Construct a feature vector from the specified record
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
                HashUtils.construct(this.bitMask, fd, categorical, input);
            } else {
                // Numerical
                H element = fd.getComponents()[0];
                HashedValue data = input.get(element);
                if (data == null) {
                    continue; // Missing
                }
                int[] featureNames = data.getCategoricals();
                double[] featureValues = data.getNumericals();

                for (int i = 0; i < featureNames.length; i++) {
                    int featureHash = HashUtils.namespace(this.bitMask, fd, featureNames[i]);
                    numerical.put(featureHash, featureValues[i]);
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

}

