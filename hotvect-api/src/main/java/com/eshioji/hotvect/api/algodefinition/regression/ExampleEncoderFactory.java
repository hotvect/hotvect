package com.eshioji.hotvect.api.algodefinition.regression;

import com.eshioji.hotvect.api.codec.regression.ExampleEncoder;
import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;

import java.util.function.Function;

public interface ExampleEncoderFactory<RECORD> extends Function<Vectorizer<RECORD>, ExampleEncoder<RECORD>> {
    @Override
    ExampleEncoder<RECORD> apply(Vectorizer<RECORD> vectorizer);
}
