package com.eshioji.hotvect.core.vectorization;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.hashed.HashedNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.combine.Combiner;
import com.eshioji.hotvect.core.hash.Hasher;
import com.eshioji.hotvect.core.transform.Transformer;

public class VectorizerImpl<R extends Enum<R> & RawNamespace, H extends Enum<H> & HashedNamespace>
        implements Vectorizer<R> {
    private final Transformer<R, H, RawValue> transformer;
    private final Hasher<H> hasher;
    private final Combiner<H> combiner;

    public VectorizerImpl(Transformer<R, H, RawValue> transformer, Hasher<H> hasher, Combiner<H> combiner) {
        this.transformer = transformer;
        this.hasher = hasher;
        this.combiner = combiner;
    }

    @Override
    public SparseVector apply(DataRecord<R, RawValue> request) {
        DataRecord<H, RawValue> parsed = transformer.apply(request);
        DataRecord<H, HashedValue> hashed = hasher.apply(parsed);
        return combiner.apply(hashed);
    }

}
