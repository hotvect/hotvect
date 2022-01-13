package com.hotvect.core.vectorization;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.hashed.HashedValue;
import com.hotvect.api.data.raw.RawValue;
import com.hotvect.api.vectorization.Vectorizer;
import com.hotvect.core.combine.Combiner;
import com.hotvect.core.hash.AuditableHasher;
import com.hotvect.core.transform.Transformer;

@Deprecated
public class VectorizerImpl<R, OUT extends Enum<OUT> & FeatureNamespace>
        implements Vectorizer<R> {
    private final Transformer<R, OUT> transformer;
    private final AuditableHasher<OUT> hasher;
    private final Combiner<OUT> combiner;

    public VectorizerImpl(Transformer<R, OUT> transformer, AuditableHasher<OUT> hasher, Combiner<OUT> combiner) {
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

}
