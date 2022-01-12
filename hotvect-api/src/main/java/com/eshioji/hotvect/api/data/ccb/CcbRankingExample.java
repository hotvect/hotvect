package com.eshioji.hotvect.api.data.ccb;

import com.eshioji.hotvect.api.data.ranking.RankingDecision;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.eshioji.hotvect.api.data.ranking.RankingRequest;
import it.unimi.dsi.fastutil.Pair;

import java.util.List;

public class CcbRankingExample<SHARED, ACTION, OUTCOME> extends RankingExample<SHARED, ACTION, OUTCOME> {
    public CcbRankingExample(RankingRequest<SHARED, ACTION> rankingRequest, List<CcbRankingOutcome<OUTCOME>> outcomes) {
        super(rankingRequest,outcomes);
    }

    @Override
    public List<CcbRankingOutcome<OUTCOME>> getOutcomes() {
        return (List<CcbRankingOutcome<OUTCOME>>) super.getOutcomes();
    }
}
