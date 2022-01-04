package com.eshioji.hotvect.api.data.raw.ccb;

import java.util.List;

public class Outcomes {
    private final List<Decision> decisions;
    private final List<Double> reward;

    public Outcomes(List<Decision> decisions, List<Double> reward) {
        this.decisions = decisions;
        this.reward = reward;
    }
}
