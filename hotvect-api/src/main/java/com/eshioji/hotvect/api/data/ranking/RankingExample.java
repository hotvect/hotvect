package com.eshioji.hotvect.api.data.ranking;

import com.eshioji.hotvect.api.data.common.Example;
import it.unimi.dsi.fastutil.Pair;

import java.util.List;

public class RankingExample<SHARED, ACTION, OUTCOME>  implements Example {
    private final RankingRequest<SHARED, ACTION> rankingRequest;
    private final List<? extends RankingOutcome<OUTCOME>> outcomes;

    public RankingExample(RankingRequest<SHARED, ACTION> rankingRequest, List<? extends RankingOutcome<OUTCOME>> outcomes) {
        this.rankingRequest = rankingRequest;
        this.outcomes = outcomes;
    }

    public RankingRequest<SHARED, ACTION> getRankingRequest() {
        return rankingRequest;
    }

    public List<? extends RankingOutcome<OUTCOME>> getOutcomes() {
        return outcomes;
    }
}
