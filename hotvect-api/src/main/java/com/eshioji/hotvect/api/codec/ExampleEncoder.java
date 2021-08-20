package com.eshioji.hotvect.api.codec;

import com.eshioji.hotvect.api.data.raw.Example;

import java.util.function.Function;

/**
 * TODO
 */
public interface ExampleEncoder<R> extends Function<Example<R>, String> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(Example<R> toEncode);
}
