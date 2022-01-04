package com.eshioji.hotvect.api.data.raw.ccb;

import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

public class Decision {
    private final int actionIndex;
    private final Double probability;
    private final Int2DoubleMap otherActions2Probabilities;

    public Decision(int actionIndex, Double probability, Int2DoubleMap otherActions2Probabilities) {
        this.actionIndex = actionIndex;
        this.probability = probability;
        this.otherActions2Probabilities = otherActions2Probabilities;
    }

    public int getActionIndex() {
        return actionIndex;
    }

    public Double getProbability() {
        return probability;
    }

    public Int2DoubleMap getOtherActions2Probabilities() {
        return otherActions2Probabilities;
    }
}
