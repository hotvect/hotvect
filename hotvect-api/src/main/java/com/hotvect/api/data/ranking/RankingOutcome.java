package com.hotvect.api.data.ranking;

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

    @Override
    public String toString() {
        return "RankingOutcome{" +
                "rankingDecision=" + rankingDecision +
                ", outcome=" + outcome +
                '}';
    }
}
