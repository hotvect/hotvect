package com.hotvect.api.codec.topk;

import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.topk.TopKExample;

import java.util.List;

public interface TopKExampleDecoder<SHARED, ACTION, OUTCOME> extends ExampleDecoder<TopKExample<SHARED, ACTION, OUTCOME>> {
    @Override
    List<TopKExample<SHARED, ACTION, OUTCOME>> apply(String toDecode);
}
