package com.eshioji.hotvect.api.codec.regression;

import com.eshioji.hotvect.api.data.raw.regression.Example;

import java.util.List;
import java.util.function.Function;

public interface ExampleDecoder<R> extends Function<String, List<Example<R>>> {
}
