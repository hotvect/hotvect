package com.hotvect.api.transformation.ranking;

import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.Computing;
import com.hotvect.api.transformation.ComputingCandidate;

import java.util.List;

@Deprecated(forRemoval = true)
public record ComputingRankingRequest<SHARED, ACTION>(
        RankingRequest<SHARED, ACTION> rankingRequest,
        Computing<SHARED> shared,
        List<ComputingCandidate<SHARED, ACTION>> candidates
) {
    // -----------------------------------------------------------------
    // Back-compat accessor methods – scheduled for removal
    // -----------------------------------------------------------------
    @Deprecated(forRemoval = true)
    public RankingRequest<SHARED, ACTION> getRankingRequest() { return rankingRequest; }

    @Deprecated(forRemoval = true)
    public Computing<SHARED> getShared() { return shared; }

    // Note: historically named "getCandidate" (singular); keep the same
    // signature so existing byte-code continues to link.
    @Deprecated(forRemoval = true)
    public List<ComputingCandidate<SHARED, ACTION>> getCandidate() { return candidates; }
}
