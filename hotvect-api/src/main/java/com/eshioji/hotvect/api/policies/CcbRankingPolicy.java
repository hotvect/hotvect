package com.eshioji.hotvect.api.policies;

import com.eshioji.hotvect.api.data.raw.ccb.Decision;
import com.eshioji.hotvect.api.data.raw.ccb.Request;

import java.util.List;
import java.util.function.Function;

public interface CcbRankingPolicy<SHARED, ACTION> extends Function<Request<SHARED, ACTION>, List<Decision>> {
    @Override
    List<Decision> apply(Request<SHARED, ACTION> request);
}
