package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public interface ExampleEncoderFactory<R> extends Function<Vectorizer<R>, ExampleEncoder<R>> {
    @Override
    ExampleEncoder<R> apply(Vectorizer<R> vectorizer);
}
