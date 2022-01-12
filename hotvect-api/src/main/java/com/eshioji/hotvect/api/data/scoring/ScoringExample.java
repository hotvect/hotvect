package com.eshioji.hotvect.api.data.scoring;

import com.eshioji.hotvect.api.data.common.Example;

import javax.annotation.Nullable;

public class ScoringExample<RECORD, OUTCOME> implements Example {
    private final String exampleId;
    private final RECORD record;
    private final OUTCOME outcome;

    public ScoringExample(String exampleId, RECORD record, OUTCOME outcome) {
        this.exampleId = exampleId;
        this.record = record;
        this.outcome = outcome;
    }

    public ScoringExample(RECORD record, OUTCOME outcome) {
        this.exampleId = null;
        this.record = record;
        this.outcome = outcome;
    }

    public RECORD getRecord() {
        return record;
    }

    public OUTCOME getOutcome() {
        return outcome;
    }

    @Nullable
    @Override
    public String getExampleId() {
        return this.exampleId;
    }
}
