package com.hotvect.api.data.ranking;

import com.hotvect.api.data.Decision;
import com.hotvect.api.data.common.Outcome;

public record RankingOutcome<OUTCOME, ACTION>(
        RankingDecision<ACTION> rankingDecision,
        OUTCOME outcome
) implements Outcome<OUTCOME, ACTION> {

    @Override
    public Decision<ACTION> decision() {
        return rankingDecision;
    }

    @Deprecated(forRemoval = true)
    public RankingDecision<ACTION> getRankingDecision() {
        return this.rankingDecision;
    }

    @Deprecated(forRemoval = true)
    public OUTCOME getOutcome() {
        return this.outcome;
    }
}
