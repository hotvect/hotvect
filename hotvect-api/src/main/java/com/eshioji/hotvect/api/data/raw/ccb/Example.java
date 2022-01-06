package com.eshioji.hotvect.api.data.raw.ccb;

import java.util.List;

public class Example<SHARED, ACTION, OUTCOME> {
    private final Request<SHARED, ACTION> request;
    private final List<Decision> decisions;
    private final List<OUTCOME> outcomes;

    public Example(Request<SHARED, ACTION> request, List<Decision> decisions, List<OUTCOME> outcomes) {
        this.request = request;
        this.decisions = decisions;
        this.outcomes = outcomes;
    }

    public Request<SHARED, ACTION> getRequest() {
        return request;
    }

    public List<Decision> getDecisions() {
        return decisions;
    }

    public List<OUTCOME> getOutcomes() {
        return outcomes;
    }
}
