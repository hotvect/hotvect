package com.hotvect.api.data.topk;

import java.util.Objects;

public class TopKOutcome<OUTCOME, ACTION> {
    private final TopKDecision<ACTION> topKDecision;
    private final OUTCOME outcome;

    public TopKOutcome(TopKDecision<ACTION> topKDecision, OUTCOME outcome) {
        this.topKDecision = topKDecision;
        this.outcome = outcome;
    }

    public TopKDecision<ACTION> getTopKDecision() {
        return topKDecision;
    }

    public OUTCOME getOutcome() {
        return outcome;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopKOutcome<?, ?> that = (TopKOutcome<?, ?>) o;
        return Objects.equals(topKDecision, that.topKDecision) && Objects.equals(outcome, that.outcome);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topKDecision, outcome);
    }

    @Override
    public String toString() {
        return "TopKOutcome{" +
                "topKDecision=" + topKDecision +
                ", outcome=" + outcome +
                '}';
    }
}
