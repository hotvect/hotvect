package com.hotvect.api.codec.topk;

import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.topk.TopKExample;

/**
 * TODO
 */
public interface TopKExampleEncoder<SHARED, ACTION, OUTCOME> extends ExampleEncoder<TopKExample<SHARED, ACTION, OUTCOME>> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(TopKExample<SHARED, ACTION, OUTCOME> toEncode);
}
