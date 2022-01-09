package com.eshioji.hotvect.api.policies;

import com.eshioji.hotvect.api.data.ranking.Decision;
import com.eshioji.hotvect.api.data.ranking.Request;

import java.util.List;
import java.util.function.Function;

public interface Ranker<SHARED, ACTION> extends Function<Request<SHARED, ACTION>, List<Decision>> {
    @Override
    List<Decision> apply(Request<SHARED, ACTION> request);
}
