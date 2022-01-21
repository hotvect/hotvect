package com.hotvect.api.data.ranking;

public class RankingDecision {
    protected final int actionIndex;
    protected final Double score;

    public RankingDecision(int actionIndex, Double score) {
        this.actionIndex = actionIndex;
        this.score = score;
    }

    public int getActionIndex() {
        return actionIndex;
    }

    public Double getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "RankingDecision{" +
                "actionIndex=" + actionIndex +
                ", score=" + score +
                '}';
    }
}
