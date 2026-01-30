package com.hotvect.api.algorithms;

import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKRequest;

public interface ThemedTopK<SHARED, ACTION> extends TopK<SHARED, ACTION>, Algorithm{
    @Override
    ThemedTopKResponse<ACTION> apply(TopKRequest<SHARED> topKRequest);
}
