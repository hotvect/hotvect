package com.eshioji.hotvect.api.data.ranking;

public class RankingOutcome<OUTCOME> {
    private final RankingDecision rankingDecision;
    private final OUTCOME outcome;

    public RankingOutcome(RankingDecision rankingDecision, OUTCOME outcome) {
        this.rankingDecision = rankingDecision;
        this.outcome = outcome;
    }

    public RankingDecision getRankingDecision() {
        return rankingDecision;
    }

    public OUTCOME getOutcome() {
        return outcome;
    }
}
