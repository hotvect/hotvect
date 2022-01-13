package com.hotvect.core.vectorization.scoring;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.vectorization.ScoringVectorizer;
import com.hotvect.core.combine.Combiner;
import com.hotvect.core.hash.AuditableHasher;
import com.hotvect.core.transform.regression.ScoringTransformer;

@Deprecated
public class ScoringVectorizerImpl<R, OUT extends Enum<OUT> & FeatureNamespace>
        implements ScoringVectorizer<R> {
    private final ScoringTransformer<R, OUT> scoringTransformer;
    private final AuditableHasher<OUT> hasher;
    private final Combiner<OUT> combiner;

    public ScoringVectorizerImpl(ScoringTransformer<R, OUT> scoringTransformer, AuditableHasher<OUT> hasher, Combiner<OUT> combiner) {
        this.scoringTransformer = scoringTransformer;
        this.hasher = hasher;
        this.combiner = combiner;
    }

    @Override
    public SparseVector apply(R request) {
        DataRecord<OUT, RawValue> parsed = scoringTransformer.apply(request);
        DataRecord<OUT, HashedValue> hashed = hasher.apply(parsed);
        return combiner.apply(hashed);
    }

}
