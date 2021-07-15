package com.eshioji.hotvect.core.hash;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.Namespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;


/**
 * A function that converts all {@link RawValue} into {@link HashedValue} by hashing any strings
 */
public class Hasher<H extends Enum<H> & Namespace> implements Function<DataRecord<H, RawValue>, DataRecord<H, HashedValue>> {
    private final Class<H> namespace;
    private final H[] namespaces;

    public Hasher(Class<H> hashedNamespace) {
        this.namespace = hashedNamespace;
        this.namespaces = hashedNamespace.getEnumConstants();
    }

    private static HashedValue hash(RawValue rawDataElementValue) {
        return switch (rawDataElementValue.getValueType()) {
            case SINGLE_STRING -> HashedValue.singleCategorical(hashSingleString(rawDataElementValue.getSingleString()));
            case STRINGS -> HashedValue.categoricals(hashStrings(rawDataElementValue.getStrings()));
            case SINGLE_NUMERICAL -> HashedValue.singleNumerical(rawDataElementValue.getSingleNumerical());
            case STRINGS_TO_NUMERICALS -> hashStringsToNumericals(rawDataElementValue.getStrings(), rawDataElementValue.getNumericals());
            case SINGLE_CATEGORICAL, CATEGORICALS, CATEGORICALS_TO_NUMERICALS -> rawDataElementValue.getHashedValue();
        };
    }

    private static int hashSingleString(String string) {
        return HashUtils.hashUnencodedChars(string);
    }

    private static int[] hashStrings(String[] strings) {
        int[] hashes = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            String string = strings[i];
            int hash = hashSingleString(string);
            hashes[i] = hash;
        }
        return hashes;
    }

    private static HashedValue hashStringsToNumericals(String[] names, double[] values) {
        int[] indices = new int[names.length];

        for (int i = 0; i < names.length; i++) {
            int hash = hashSingleString(names[i]);
            indices[i] = hash;
        }
        return HashedValue.numericals(indices, values);
    }

    /**
     * Hash the given raw {@link DataRecord} to yield a hashed {@link DataRecord}.
     * Note that this class does not hash integers (it only hashes strings).
     * @param input the raw {@link DataRecord} to hash
     * @return hashed {@link DataRecord}
     */
    @Override
    public DataRecord<H, HashedValue> apply(DataRecord<H, RawValue> input) {
        DataRecord<H, HashedValue> ret = new DataRecord<>(namespace);
        for (H h : namespaces) {
            final RawValue toHash = input.get(h);
            if (toHash == null) {
                continue;
            }

            final HashedValue hashed = hash(toHash);
            ret.put(h, hashed);
        }
        return ret;
    }

}
