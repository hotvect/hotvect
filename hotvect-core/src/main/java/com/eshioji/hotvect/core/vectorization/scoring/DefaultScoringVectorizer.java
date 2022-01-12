package com.eshioji.hotvect.core.vectorization.scoring;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.HashedValue;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.audit.AuditableCombiner;
import com.eshioji.hotvect.core.audit.AuditableScoringVectorizer;
import com.eshioji.hotvect.core.audit.HashedFeatureName;
import com.eshioji.hotvect.core.audit.RawFeatureName;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.regression.ScoringTransformer;

import java.util.List;
import java.util.Map;

public class DefaultScoringVectorizer<RECORD, OUT extends Enum<OUT> & FeatureNamespace> implements AuditableScoringVectorizer<RECORD> {
    private final ScoringTransformer<RECORD, OUT> scoringTransformer;
    private final AuditableHasher<OUT> hasher;
    private final AuditableCombiner<OUT> combiner;

    public DefaultScoringVectorizer(ScoringTransformer<RECORD, OUT> scoringTransformer, AuditableHasher<OUT> hasher, AuditableCombiner<OUT> combiner) {
        this.scoringTransformer = scoringTransformer;
        this.hasher = hasher;
        this.combiner = combiner;
    }

    @Override
    public SparseVector apply(RECORD request) {
        DataRecord<OUT, RawValue> parsed = scoringTransformer.apply(request);
        DataRecord<OUT, HashedValue> hashed = hasher.apply(parsed);
        return combiner.apply(hashed);
    }

    @Override
    public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit() {
        ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue = hasher.enableAudit();
        return this.combiner.enableAudit(featureName2SourceRawValue);
    }
}
