package com.eshioji.hotvect.core.hash;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.HashedValue;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.audit.HashedFeatureName;
import com.eshioji.hotvect.core.audit.HasherAuditState;
import com.eshioji.hotvect.core.audit.RawFeatureName;

import java.util.Map;
import java.util.function.Function;


/**
 * A function that converts all {@link RawValue} into {@link HashedValue} by hashing any strings
 */
public class AuditableHasher<FEATURE extends Enum<FEATURE> & FeatureNamespace> implements Function<DataRecord<FEATURE, RawValue>, DataRecord<FEATURE, HashedValue>> {
    private HasherAuditState auditState;
    private final Class<FEATURE> namespace;
    private final FEATURE[] namespaces;

    public AuditableHasher(Class<FEATURE> hashedNamespace) {
        this.namespace = hashedNamespace;
        this.namespaces = hashedNamespace.getEnumConstants();
        this.auditState = null;
    }

    public ThreadLocal<Map<HashedFeatureName, RawFeatureName>> enableAudit() {
        this.auditState = new HasherAuditState();
        return this.auditState.getFeatureName2SourceRawValue();
    }


    /**
     * Hash the given raw {@link DataRecord} to yield a hashed {@link DataRecord}.
     * Note that this class does not hash integers (it only hashes strings).
     *
     * @param input the raw {@link DataRecord} to hash
     * @return hashed {@link DataRecord}
     */
    @Override
    public DataRecord<FEATURE, HashedValue> apply(DataRecord<FEATURE, RawValue> input) {
        if (auditState != null) {
            // Audit is enabled. Hasher is the first entry point, so we clear our thread local cache to prepare for
            // the audit of this invocation
            auditState.getFeatureName2SourceRawValue().get().clear();
        }


        DataRecord<FEATURE, HashedValue> ret = new DataRecord<>(namespace);
        for (FEATURE namespace : namespaces) {
            final RawValue toHash = input.get(namespace);
            if (toHash == null) {
                continue;
            }

            final HashedValue hashed = HashUtils.hash(toHash);
            ret.put(namespace, hashed);

            if (auditState != null) {
                // Audit enabled
                auditState.registerSourceRawValue(namespace, toHash, hashed);
            }
        }
        return ret;
    }

}
