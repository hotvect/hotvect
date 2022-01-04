package com.eshioji.hotvect.api.codec.ccb;

import com.eshioji.hotvect.api.data.raw.ccb.Example;

import java.util.function.Function;

/**
 * TODO
 */
public interface ExampleEncoder<SHARED, ACTION> extends Function<Example<SHARED, ACTION>, String> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(Example<SHARED, ACTION> toEncode);
}
