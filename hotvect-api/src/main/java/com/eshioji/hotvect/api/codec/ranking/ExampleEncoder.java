package com.eshioji.hotvect.api.codec.ranking;

import com.eshioji.hotvect.api.data.ranking.Example;

import java.util.function.Function;

/**
 * TODO
 */
public interface ExampleEncoder<SHARED, ACTION, OUTCOME> extends Function<Example<SHARED, ACTION, OUTCOME>, String> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(Example<SHARED, ACTION, OUTCOME> toEncode);
}
