package com.hotvect.api.codec.common;

import com.hotvect.api.data.common.Example;

import java.util.Optional;
import java.util.function.Function;

public interface ExampleEncoder<EXAMPLE extends Example> extends Function<EXAMPLE, String> {

    /**
     * Used by algorithms that need a separate schema description, like catboost
     * @return
     */
    default Optional<String> schemaDescription(){
        return Optional.empty();
    }
}
