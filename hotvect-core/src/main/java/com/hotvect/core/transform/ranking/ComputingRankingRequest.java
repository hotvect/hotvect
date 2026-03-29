package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.core.transform.Computable;

import java.util.List;

public record ComputingRankingRequest<SHARED, ACTION>(
        RankingRequest<SHARED, ACTION> rankingRequest,
        Computable<RankingRequest<SHARED, ACTION>> shared,
        List<ComputingCandidate<SHARED, ACTION>> candidates
) {
}
