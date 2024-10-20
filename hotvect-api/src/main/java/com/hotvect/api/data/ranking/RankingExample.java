package com.hotvect.api.data.ranking;

import com.hotvect.api.data.common.Example;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

public class RankingExample<SHARED, ACTION, OUTCOME> implements Example {
    private final String exampleId;
    private final RankingRequest<SHARED, ACTION> rankingRequest;
    private final List<RankingOutcome<OUTCOME, ACTION>> outcomes;

    public RankingExample(String exampleId, RankingRequest<SHARED, ACTION> rankingRequest, List<RankingOutcome<OUTCOME, ACTION>> outcomes) {
        this.exampleId = exampleId;
        this.rankingRequest = rankingRequest;
        this.outcomes = outcomes;
    }


    public RankingRequest<SHARED, ACTION> getRankingRequest() {
        return rankingRequest;
    }

    public List<RankingOutcome<OUTCOME, ACTION>> getOutcomes() {
        return outcomes;
    }

    @Nonnull
    @Override
    public String getExampleId() {
        return this.exampleId;
    }

    @Override
    public String toString() {
        return "RankingExample{" +
                "exampleId='" + exampleId + '\'' +
                ", rankingRequest=" + rankingRequest +
                ", outcomes=" + outcomes +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RankingExample<?, ?, ?> that = (RankingExample<?, ?, ?>) o;
        return exampleId.equals(that.exampleId) && rankingRequest.equals(that.rankingRequest) && Objects.equals(outcomes, that.outcomes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exampleId, rankingRequest, outcomes);
    }
}
