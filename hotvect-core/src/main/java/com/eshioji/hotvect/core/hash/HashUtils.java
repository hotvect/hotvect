package com.eshioji.hotvect.core.hash;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.google.common.primitives.Chars;
import com.google.common.primitives.Ints;
import it.unimi.dsi.fastutil.ints.IntCollection;

/**
 * Hashing related utility codes
 */
public class HashUtils {
    public static final int FNV1_32_INIT = 0x811c9dc5;
    public static final int FNV1_PRIME_32 = 16777619;

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



    public static <C extends Enum<C> & FeatureNamespace> int namespace(int mask, FeatureDefinition<C> fd, int featureName) {
        return ((fd.getFeatureNamespace() * FNV1_PRIME_32) ^ HashUtils.hashInt(featureName)) & mask;
    }

    public static int hashInts(int... ints){
        var ret = FNV1_32_INIT;
        for (int i : ints) {
            ret ^= i;
            ret *= FNV1_PRIME_32;
        }
        return ret;
    }
}
