package com.eshioji.hotvect.api.data.scoring;

import com.eshioji.hotvect.api.data.common.Example;

public class ScoringExample<RECORD, OUTCOME> implements Example {
    private final RECORD record;
    private final OUTCOME outcome;

    public ScoringExample(RECORD record, OUTCOME outcome) {
        this.record = record;
        this.outcome = outcome;
    }

    public RECORD getRecord() {
        return record;
    }

    public OUTCOME getOutcome() {
        return outcome;
    }

    @Override
    public String toString() {
        return "ScoringExample{" +
                "record=" + record +
                ", outcome=" + outcome +
                '}';
    }
}
