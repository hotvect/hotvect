package com.hotvect.api.data.topk;

import com.hotvect.api.data.Decision;
import com.hotvect.api.data.common.Outcome;

public record TopKOutcome<OUTCOME, ACTION>(
        TopKDecision<ACTION> topKDecision,
        OUTCOME outcome
) implements Outcome<OUTCOME, ACTION> {

    @Override
    public Decision<ACTION> decision() {
        return topKDecision;
    }
}
