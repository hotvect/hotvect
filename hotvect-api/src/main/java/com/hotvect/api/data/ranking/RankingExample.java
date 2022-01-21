package com.hotvect.api.data.ranking;

import com.hotvect.api.data.common.Example;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class RankingExample<SHARED, ACTION, OUTCOME> implements Example {
    private final String exampleId;
    private final RankingRequest<SHARED, ACTION> rankingRequest;
    private final List<? extends RankingOutcome<OUTCOME>> outcomes;

    public RankingExample(String exampleId, RankingRequest<SHARED, ACTION> rankingRequest, List<? extends RankingOutcome<OUTCOME>> outcomes) {
        this.exampleId = exampleId;
        this.rankingRequest = rankingRequest;
        this.outcomes = outcomes;
    }


    public RankingRequest<SHARED, ACTION> getRankingRequest() {
        return rankingRequest;
    }

    public List<? extends RankingOutcome<OUTCOME>> getOutcomes() {
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
}
