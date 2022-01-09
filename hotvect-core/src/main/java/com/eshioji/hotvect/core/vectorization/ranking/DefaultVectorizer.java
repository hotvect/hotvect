package com.eshioji.hotvect.core.vectorization.ranking;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.HashedValue;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.api.data.ranking.Request;
import com.eshioji.hotvect.api.vectorization.ranking.Vectorizer;
import com.eshioji.hotvect.core.audit.AuditableCombiner;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.ranking.Transformer;
import com.eshioji.hotvect.core.util.ListTransform;

import java.util.List;

public class DefaultVectorizer<SHARED, ACTION, OUT extends Enum<OUT> & FeatureNamespace> implements Vectorizer<SHARED, ACTION> {
    private final Transformer<SHARED, ACTION, OUT> transformer;
    private final AuditableHasher<OUT> hasher;
    private final AuditableCombiner<OUT> combiner;

    public DefaultVectorizer(Transformer<SHARED, ACTION, OUT> transformer, AuditableHasher<OUT> hasher, AuditableCombiner<OUT> combiner) {
        this.transformer = transformer;
        this.hasher = hasher;
        this.combiner = combiner;
    }

    @Override
    public List<SparseVector> apply(Request<SHARED, ACTION> request) {
        List<DataRecord<OUT, RawValue>> transformed = transformer.apply(request.getShared(), request.getAvailableActions());
        List<DataRecord<OUT, HashedValue>> hashed = ListTransform.map(transformed, hasher);
        return ListTransform.map(hashed, combiner);
    }
}
