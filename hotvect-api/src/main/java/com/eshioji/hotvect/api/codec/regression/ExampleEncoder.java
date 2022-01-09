package com.eshioji.hotvect.api.codec.regression;

import com.eshioji.hotvect.api.data.regression.Example;

import java.util.function.Function;

/**
 * TODO
 */
public interface ExampleEncoder<RECORD> extends Function<Example<RECORD>, String> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(Example<RECORD> toEncode);
}
