package com.eshioji.hotvect.core.hash;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.hashed.HashedNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.google.common.primitives.Chars;
import com.google.common.primitives.Ints;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.IntCollection;

/**
 * Hashing related utility codes
 */
public class HashUtils {
    private static final int MULTIPLIER = 16777619;

    // Code adapted from Google Guava, which is licenced under Apache License 2.0
    // ---- start ---
    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private static int mixK1(int k1) {
        k1 *= C1;
        k1 = Integer.rotateLeft(k1, 15);
        k1 *= C2;
        return k1;
    }

    private static int mixH1(int h1, int k1) {
        h1 ^= k1;
        h1 = Integer.rotateLeft(h1, 13);
        h1 = h1 * 5 + 0xe6546b64;
        return h1;
    }

    // Finalization mix - force all bits of a hash block to avalanche
    private static int fmix(int h1, int length) {
        h1 ^= length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    public static int hashInt(int input) {
        int k1 = mixK1(input);
        int h1 = mixH1(0, k1);
        return fmix(h1, Ints.BYTES);
    }

    public static int hashUnencodedChars(CharSequence input) {
        int h1 = 0;

        // step through the CharSequence 2 chars at a time
        for (int i = 1; i < input.length(); i += 2) {
            int k1 = input.charAt(i - 1) | (input.charAt(i) << 16);
            k1 = mixK1(k1);
            h1 = mixH1(h1, k1);
        }

        // deal with any remaining characters
        if ((input.length() & 1) == 1) {
            int k1 = input.charAt(input.length() - 1);
            k1 = mixK1(k1);
            h1 ^= k1;
        }

        return fmix(h1, Chars.BYTES * input.length());
    }
    // ---- end ---

    /**
     * Given a {@link DataRecord}, add the specified interactions to the accumulating {@link IntCollection}
     * @param mask Bitmask to be used for feature hashing
     * @param featureDefinition Definition of features, which may include interaction features
     * @param acc Accumulator to which feaure hashes will be added
     * @param record Input hashed {@link DataRecord}
     * @param <H> the {@link HashedNamespace} to be used
     */
    public static <H extends Enum<H> & HashedNamespace> void construct(int mask,
                                                                       FeatureDefinition<H> featureDefinition,
                                                                       IntCollection acc,
                                                                       DataRecord<H, HashedValue> record) {
        H[] toInteract = featureDefinition.getComponents();
        if (toInteract.length == 1) {
            // There is only one component
            HashedValue value = record.get(toInteract[0]);
            if (value != null) {
                for (int el : value.getCategoricals()) {
                    int hash = (featureDefinition.getFeatureNamespace() * MULTIPLIER) ^ HashUtils.hashInt(el);
                    acc.add(hash & mask);
                }
            }
        } else {
            // There are more than one component - it is a interaction feature
            interact(mask, featureDefinition, acc, record);
        }
    }

    protected static <H extends Enum<H> & HashedNamespace> void interact(int mask,
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
            solutions *= data.getCategoricals().length;
        }

        for (int i = 0; i < solutions; i++) {
            int j = 1;
            int n = fd.getFeatureNamespace();

            for (H h : toInteract) {
                int[] set = values.get(h).getCategoricals();
                int el = set[(i / j) % set.length];
                n ^= HashUtils.hashInt(el);
                n *= MULTIPLIER;
                j *= set.length;
            }
            int ret = n & mask;
            acc.add(ret);
        }
    }


    public static <C extends Enum<C> & HashedNamespace> int namespace(int mask, FeatureDefinition<C> fd, int featureName) {
        return ((fd.getFeatureNamespace() * MULTIPLIER) ^ HashUtils.hashInt(featureName)) & mask;
    }
}
