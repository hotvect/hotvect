package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

import java.util.Map;
import java.util.function.Function;

public interface EagerRankingTransformation<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, Map<Namespace, Object>> {
    @Override
    Map<Namespace, Object> apply(RankingRequest<SHARED, ACTION> rankingRequest);
}
