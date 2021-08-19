package com.eshioji.hotvect.core.vectorization;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;

public interface Vectorizer<R>
        extends Function<R, SparseVector> {
}

