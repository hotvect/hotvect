package com.eshioji.hotvect.api.codec.common;

import com.eshioji.hotvect.api.data.common.Example;

import java.util.List;
import java.util.function.Function;

public interface ExampleDecoder<EXAMPLE extends Example> extends Function<String, List<EXAMPLE>> {
}
