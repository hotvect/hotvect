package com.hotvect.api.data.ranking;

import java.util.Objects;

public class RankingOutcome<OUTCOME, ACTION> {
    private final RankingDecision<ACTION> rankingDecision;
    private final OUTCOME outcome;

    public RankingOutcome(RankingDecision<ACTION> rankingDecision, OUTCOME outcome) {
        this.rankingDecision = rankingDecision;
        this.outcome = outcome;
    }

    public RankingDecision<ACTION> getRankingDecision() {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RankingOutcome<?, ?> that = (RankingOutcome<?, ?>) o;
        return rankingDecision.equals(that.rankingDecision) && Objects.equals(outcome, that.outcome);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rankingDecision, outcome);
    }
}
