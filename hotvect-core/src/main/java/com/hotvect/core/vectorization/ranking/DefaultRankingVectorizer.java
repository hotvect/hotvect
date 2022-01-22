package com.hotvect.core.vectorization.ranking;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.vectorization.RankingVectorizer;
import com.hotvect.core.audit.AuditableCombiner;
import com.hotvect.core.audit.AuditableRankingVectorizer;
import com.hotvect.core.audit.HashedFeatureName;
import com.hotvect.core.audit.RawFeatureName;
import com.hotvect.core.hash.AuditableHasher;
import com.hotvect.core.transform.ranking.RankingTransformer;
import com.hotvect.core.util.ListTransform;

import java.util.List;
import java.util.Map;

public class DefaultRankingVectorizer<SHARED, ACTION, OUT extends Enum<OUT> & FeatureNamespace> implements AuditableRankingVectorizer<SHARED, ACTION> {
    private final RankingTransformer<SHARED, ACTION, OUT> rankingTransformer;
    private final AuditableHasher<OUT> hasher;
    private final AuditableCombiner<OUT> combiner;

    public DefaultRankingVectorizer(RankingTransformer<SHARED, ACTION, OUT> rankingTransformer, AuditableHasher<OUT> hasher, AuditableCombiner<OUT> combiner) {
        this.rankingTransformer = rankingTransformer;
        this.hasher = hasher;
        this.combiner = combiner;
    }

    @Override
    public List<SparseVector> apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        combiner.clearAuditState();
        hasher.clearAuditState();
        List<DataRecord<OUT, RawValue>> transformed = rankingTransformer.apply(rankingRequest.getShared(), rankingRequest.getAvailableActions());
        List<DataRecord<OUT, HashedValue>> hashed = ListTransform.map(transformed, hasher);
        return ListTransform.map(hashed, combiner);
    }

    @Override
    public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit() {
        ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue = hasher.enableAudit();
        return this.combiner.enableAudit(featureName2SourceRawValue);
    }
}
