package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.codec.regression.ExampleEncoder;
import com.eshioji.hotvect.api.vectorization.Vectorizer;

import java.util.function.Function;

public interface ExampleEncoderFactory<R> extends Function<Vectorizer<R>, ExampleEncoder<R>> {
    @Override
    ExampleEncoder<R> apply(Vectorizer<R> vectorizer);
}
