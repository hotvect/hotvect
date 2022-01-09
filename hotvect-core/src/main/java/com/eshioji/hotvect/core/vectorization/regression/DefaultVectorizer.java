package com.eshioji.hotvect.core.vectorization.regression;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.HashedValue;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.audit.AuditableCombiner;
import com.eshioji.hotvect.core.audit.AuditableVectorizer;
import com.eshioji.hotvect.core.audit.HashedFeatureName;
import com.eshioji.hotvect.core.audit.RawFeatureName;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.regression.Transformer;

import java.util.List;
import java.util.Map;

public class DefaultVectorizer<RECORD, OUT extends Enum<OUT> & FeatureNamespace> implements AuditableVectorizer<RECORD> {
    private final Transformer<RECORD, OUT> transformer;
    private final AuditableHasher<OUT> hasher;
    private final AuditableCombiner<OUT> combiner;

    public DefaultVectorizer(Transformer<RECORD, OUT> transformer, AuditableHasher<OUT> hasher, AuditableCombiner<OUT> combiner) {
        this.transformer = transformer;
        this.hasher = hasher;
        this.combiner = combiner;
    }

    @Override
    public SparseVector apply(RECORD request) {
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
