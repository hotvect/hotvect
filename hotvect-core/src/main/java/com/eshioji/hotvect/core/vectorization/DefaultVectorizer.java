package com.eshioji.hotvect.core.vectorization;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.audit.AuditableCombiner;
import com.eshioji.hotvect.core.audit.AuditableVectorizer;
import com.eshioji.hotvect.core.audit.HashedFeatureName;
import com.eshioji.hotvect.core.audit.RawFeatureName;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.Transformer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

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
