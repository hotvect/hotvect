package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.codec.FlatmapExampleDecoder;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public interface FlatmapExampleDecoderFactory<R> extends Supplier<FlatmapExampleDecoder<R>> {
}
