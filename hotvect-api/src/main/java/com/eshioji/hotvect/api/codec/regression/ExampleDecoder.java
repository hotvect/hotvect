package com.eshioji.hotvect.api.codec.regression;

import com.eshioji.hotvect.api.data.regression.Example;

import java.util.List;
import java.util.function.Function;

public interface ExampleDecoder<RECORD> extends Function<String, List<Example<RECORD>>> {
}
