package com.hotvect.api.data.scoring;

import com.hotvect.api.data.common.Example;

import javax.annotation.Nullable;
import java.util.Objects;

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

    @Override
    public String toString() {
        return "ScoringExample{" +
                "exampleId='" + exampleId + '\'' +
                ", record=" + record +
                ", outcome=" + outcome +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScoringExample<?, ?> that = (ScoringExample<?, ?>) o;
        return Objects.equals(exampleId, that.exampleId) && record.equals(that.record) && Objects.equals(outcome, that.outcome);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exampleId, record, outcome);
    }
}
