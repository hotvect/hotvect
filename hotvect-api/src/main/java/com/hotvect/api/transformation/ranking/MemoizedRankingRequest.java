package com.hotvect.api.transformation.ranking;

import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.memoization.Computing;
import com.hotvect.api.transformation.memoization.ComputingCandidate;

import java.util.List;

public class MemoizedRankingRequest<SHARED, ACTION> {
    private final RankingRequest<SHARED, ACTION> rankingRequest;

    private final Computing<SHARED> shared;
    private final List<ComputingCandidate<SHARED, ACTION>> action;

    public MemoizedRankingRequest(RankingRequest<SHARED, ACTION> rankingRequest, Computing<SHARED> shared, List<ComputingCandidate<SHARED, ACTION>> action) {
        this.rankingRequest = rankingRequest;
        this.shared = shared;
        this.action = action;
    }

    public RankingRequest<SHARED, ACTION> getRankingRequest() {
        return rankingRequest;
    }

    public Computing<SHARED> getShared() {
        return shared;
    }

    public List<ComputingCandidate<SHARED, ACTION>> getAction() {
        return action;
    }
}
