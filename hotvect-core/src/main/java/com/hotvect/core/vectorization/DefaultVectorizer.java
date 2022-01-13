package com.hotvect.core.vectorization;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.hashed.HashedValue;
import com.hotvect.api.data.raw.RawValue;
import com.hotvect.core.audit.AuditableCombiner;
import com.hotvect.core.audit.AuditableVectorizer;
import com.hotvect.core.audit.HashedFeatureName;
import com.hotvect.core.audit.RawFeatureName;
import com.hotvect.core.hash.AuditableHasher;
import com.hotvect.core.transform.Transformer;

import java.util.List;
import java.util.Map;

public class DefaultVectorizer<R, OUT extends Enum<OUT> & FeatureNamespace> implements AuditableVectorizer<R> {
    private final Transformer<R, OUT> transformer;
    private final AuditableHasher<OUT> hasher;
    private final AuditableCombiner<OUT> combiner;

    public DefaultVectorizer(Transformer<R, OUT> transformer, AuditableHasher<OUT> hasher, AuditableCombiner<OUT> combiner) {
        this.transformer = transformer;
        this.hasher = hasher;
        this.combiner = combiner;
    }

    @Override
    public SparseVector apply(R request) {
        DataRecord<OUT, RawValue> parsed = transformer.apply(request);
        DataRecord<OUT, HashedValue> hashed = hasher.apply(parsed);
        return combiner.apply(hashed);
    }

    @Override
    public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit() {
        ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue = hasher.enableAudit();
        return this.combiner.enableAudit(featureName2SourceRawValue);
    }
}
