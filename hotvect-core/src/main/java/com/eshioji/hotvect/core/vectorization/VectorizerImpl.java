package com.eshioji.hotvect.core.vectorization;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.hashed.HashedNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.combine.Combiner;
import com.eshioji.hotvect.core.hash.Hasher;
import com.eshioji.hotvect.core.transform.Transformer;

public class VectorizerImpl<R, OUT extends Enum<OUT> & HashedNamespace>
        implements Vectorizer<R> {
    private final Transformer<R, OUT> transformer;
    private final Hasher<OUT> hasher;
    private final Combiner<OUT> combiner;

    public VectorizerImpl(Transformer<R, OUT> transformer, Hasher<OUT> hasher, Combiner<OUT> combiner) {
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
