package com.eshioji.hotvect.core.vectorization;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;

public interface Vectorizer<IN extends Enum<IN> & RawNamespace>
        extends Function<DataRecord<IN, RawValue>, SparseVector> {
}

