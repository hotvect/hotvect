package com.hotvect.core.vectorize.ranking;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.ranking.MemoizableRankingTransformer;
import com.hotvect.api.transformation.ranking.MemoizableRankingVectorizer;
import com.hotvect.api.transformation.ranking.MemoizedRankingRequest;
import com.hotvect.core.audit.AuditableCombiner;
import com.hotvect.core.hash.AuditableHasher;
import com.hotvect.utils.ListTransform;

import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;

public class DefaultRankingVectorizer<SHARED, ACTION> implements MemoizableRankingVectorizer<SHARED, ACTION> {
    private final MemoizableRankingTransformer<SHARED, ACTION> rankingTransformer;

    private final Function<NamespacedRecord<FeatureNamespace, RawValue>, SparseVector> transformation;

    public DefaultRankingVectorizer(MemoizableRankingTransformer<SHARED, ACTION> rankingTransformer, AuditableHasher<FeatureNamespace> hasher, AuditableCombiner<FeatureNamespace> combiner) {
        this.rankingTransformer = rankingTransformer;
        this.transformation = hasher.andThen(combiner);
    }

    @Override
    public List<SparseVector> apply(MemoizedRankingRequest<SHARED, ACTION> input) {
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformed = rankingTransformer.apply(input);
        return ListTransform.map(transformed, transformation);
    }

    @Override
    public SortedSet<? extends FeatureNamespace> getUsedFeatures() {
        return this.rankingTransformer.getUsedFeatures();
    }

    @Override
    public MemoizedRankingRequest<SHARED, ACTION> memoize(RankingRequest<SHARED, ACTION> rankingRequest) {
        return this.rankingTransformer.memoize(rankingRequest);
    }

    @Override
    public MemoizedRankingRequest<SHARED, ACTION> memoize(MemoizedRankingRequest<SHARED, ACTION> memoizedRankingRequest) {
        return this.rankingTransformer.memoize(memoizedRankingRequest);
    }
}
