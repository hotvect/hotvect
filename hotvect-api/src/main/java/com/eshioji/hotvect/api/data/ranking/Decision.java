package com.eshioji.hotvect.api.data.ranking;

public class Decision {
    protected final int actionIndex;
    public Decision(int actionIndex) {
        this.actionIndex = actionIndex;
    }

    public int getActionIndex() {
        return actionIndex;
    }
}
