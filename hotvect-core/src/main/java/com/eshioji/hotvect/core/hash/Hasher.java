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

            final HashedValue hashed = HashUtils.hash(toHash);
            ret.put(h, hashed);
        }
        return ret;
    }

}
