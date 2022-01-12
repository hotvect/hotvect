package com.eshioji.hotvect.api.data.ranking;

public class RankingDecision {
    protected final int actionIndex;
    public RankingDecision(int actionIndex) {
        this.actionIndex = actionIndex;
    }

    public int getActionIndex() {
        return actionIndex;
    }
}
