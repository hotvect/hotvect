package com.eshioji.hotvect.api.data.raw.ccb;

public class Example<SHARED, ACTION> {
    private final Options<SHARED, ACTION> options;
    private final Outcomes outcomes;

    public Example(Options<SHARED, ACTION> options, Outcomes outcomes) {
        this.options = options;
        this.outcomes = outcomes;
    }

    public Options<SHARED, ACTION> getOptions() {
        return options;
    }

    public Outcomes getOutcomes() {
        return outcomes;
    }
}
