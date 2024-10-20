package com.hotvect.core.hash;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;

import java.util.function.Function;


/**
 * A function that converts all {@link RawValue} into {@link HashedValue} by hashing any strings
 */
public class AuditableHasher<FEATURE extends FeatureNamespace> implements Function<NamespacedRecord<FEATURE, RawValue>, NamespacedRecord<FEATURE, HashedValue>> {
//    private HasherAuditState auditState;
//
//    public AuditableHasher() {
//        this.auditState = null;
//    }
//
//    public ThreadLocal<Map<HashedFeatureName, RawFeatureName>> enableAudit() {
//        this.auditState = new HasherAuditState();
//        return this.auditState.getFeatureName2SourceRawValue();
//    }
//
//    public void clearAuditState() {
//        if (this.auditState != null) {
//            this.auditState.clear();
//        }
//    }


    /**
     * Hash the given raw {@link com.hotvect.api.data.DataRecord} to yield a hashed {@link com.hotvect.api.data.DataRecord}.
     * Note that this class does not hash integers (it only hashes strings).
     *
     * @param input the raw {@link com.hotvect.api.data.DataRecord} to hash
     * @return hashed {@link com.hotvect.api.data.DataRecord}
     */
    @Override
    public NamespacedRecord<FEATURE, HashedValue> apply(NamespacedRecord<FEATURE, RawValue> input) {
        NamespacedRecord<FEATURE, HashedValue> ret = new NamespacedRecordImpl<>();
        for (FEATURE k : input.asMap().keySet()) {
            final RawValue toHash = input.get(k);
            if (toHash == null) {
                continue;
            }

            HashedValue hashed = toHash.getCachedHashedValue();
            if (hashed == null){
                hashed = HashUtils.hash(toHash);
                toHash.setCachedHashedValue(hashed);
            }
//            HashedValue hashed = HashUtils.hash(toHash);

            ret.put(k, hashed);

//            if (auditState != null) {
//                // Audit enabled
//                auditState.registerSourceRawValue(k, toHash, hashed);
//            }
        }
        return ret;
    }
}
