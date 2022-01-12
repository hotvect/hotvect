package com.eshioji.hotvect.core.vectorization.ranking;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.HashedValue;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.api.data.ranking.RankingRequest;
import com.eshioji.hotvect.api.vectorization.RankingVectorizer;
import com.eshioji.hotvect.core.audit.AuditableCombiner;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.ranking.RankingTransformer;
import com.eshioji.hotvect.core.util.ListTransform;

import java.util.List;

public class DefaultRankingVectorizer<SHARED, ACTION, OUT extends Enum<OUT> & FeatureNamespace> implements RankingVectorizer<SHARED, ACTION> {
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
        List<DataRecord<OUT, RawValue>> transformed = rankingTransformer.apply(rankingRequest.getShared(), rankingRequest.getAvailableActions());
        List<DataRecord<OUT, HashedValue>> hashed = ListTransform.map(transformed, hasher);
        return ListTransform.map(hashed, combiner);
    }
}
