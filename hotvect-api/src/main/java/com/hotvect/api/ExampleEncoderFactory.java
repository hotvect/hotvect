package com.hotvect.api;

import com.hotvect.api.codec.ExampleEncoder;
import com.hotvect.api.vectorization.Vectorizer;

import java.util.function.Function;

public interface ExampleEncoderFactory<R> extends Function<Vectorizer<R>, ExampleEncoder<R>> {
    @Override
    ExampleEncoder<R> apply(Vectorizer<R> vectorizer);
}
