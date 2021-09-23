package com.eshioji.hotvect.core.hash;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.audit.HasherAuditState;
import com.eshioji.hotvect.core.audit.HashedFeatureName;
import com.eshioji.hotvect.core.audit.RawFeatureName;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import it.unimi.dsi.fastutil.ints.IntCollection;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static com.eshioji.hotvect.core.hash.HashUtils.FNV1_PRIME_32;


/**
 * A function that converts all {@link RawValue} into {@link HashedValue} by hashing any strings
 */
public class AuditableHasher<H extends Enum<H> & FeatureNamespace> implements Function<DataRecord<H, RawValue>, DataRecord<H, HashedValue>> {
    private HasherAuditState auditState;
    private final Class<H> namespace;
    private final H[] namespaces;

    public AuditableHasher(Class<H> hashedNamespace) {
        this.namespace = hashedNamespace;
        this.namespaces = hashedNamespace.getEnumConstants();
        this.auditState = null;
    }

    public ConcurrentMap<HashedFeatureName, RawFeatureName> enableAudit(){
        this.auditState = new HasherAuditState();
        return this.auditState.getFeatureName2SourceRawValue();
    }

    private static HashedValue hash(RawValue rawDataElementValue) {
        switch (rawDataElementValue.getValueType()) {
            case SINGLE_STRING: return HashedValue.singleCategorical(hashSingleString(rawDataElementValue.getSingleString()));
            case STRINGS: return HashedValue.categoricals(hashStrings(rawDataElementValue.getStrings()));
            case SINGLE_NUMERICAL: return HashedValue.singleNumerical(rawDataElementValue.getSingleNumerical());
            case STRINGS_TO_NUMERICALS: return hashStringsToNumericals(rawDataElementValue.getStrings(), rawDataElementValue.getNumericals());
            case SINGLE_CATEGORICAL:
            case CATEGORICALS:
            case CATEGORICALS_TO_NUMERICALS: return rawDataElementValue.getHashedValue();
            default: throw new AssertionError();
        }
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
        for (H namespace : namespaces) {
            final RawValue toHash = input.get(namespace);
            if (toHash == null) {
                continue;
            }

            final HashedValue hashed = hash(toHash);
            ret.put(namespace, hashed);

            if (auditState != null){
                // Audit enabled
                auditState.registerSourceRawValue(namespace, toHash, hashed);
            }
        }
        return ret;
    }

}
