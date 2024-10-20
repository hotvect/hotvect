package com.hotvect.api.algorithms;

import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;

import java.util.function.Function;

public interface TopK<SHARED, ACTION> extends Function<TopKRequest<SHARED, ACTION>, TopKResponse<ACTION>>, Algorithm{
    @Override
    TopKResponse<ACTION> apply(TopKRequest<SHARED, ACTION> topKRequest);
}
