package com.hotvect.api.data.topk;

import com.hotvect.api.data.common.Example;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

public class TopKExample<SHARED, ACTION, OUTCOME> implements Example {
    private final String exampleId;
    private final TopKRequest<SHARED, ACTION> topKRequest;
    private final List<TopKOutcome<OUTCOME, ACTION>> outcomes;

    public TopKExample(String exampleId, TopKRequest<SHARED, ACTION> topKRequest, List<TopKOutcome<OUTCOME, ACTION>> outcomes) {
        this.exampleId = checkNotNull(exampleId);
        this.topKRequest = checkNotNull(topKRequest);
        this.outcomes = checkNotNull(outcomes);
    }

    @Nonnull
    @Override
    public String getExampleId() {
        return exampleId;
    }

    public TopKRequest<SHARED, ACTION> getTopKRequest() {
        return topKRequest;
    }

    public List<TopKOutcome<OUTCOME, ACTION>> getOutcomes() {
        return outcomes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopKExample<?, ?, ?> that = (TopKExample<?, ?, ?>) o;
        return Objects.equals(exampleId, that.exampleId) && Objects.equals(topKRequest, that.topKRequest) && Objects.equals(outcomes, that.outcomes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exampleId, topKRequest, outcomes);
    }

    @Override
    public String toString() {
        return "TopKExample{" +
                "exampleId='" + exampleId + '\'' +
                ", topKRequest=" + topKRequest +
                ", outcomes=" + outcomes +
                '}';
    }
}
