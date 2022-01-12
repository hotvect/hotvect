package com.eshioji.hotvect.offlineutils.export;

import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;
import com.eshioji.hotvect.api.algorithms.Ranker;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.eshioji.hotvect.api.data.ranking.RankingOutcome;
import com.eshioji.hotvect.core.util.ListTransform;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.math.DoubleMath;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public class RankingResultFormatter<SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, Ranker<SHARED, ACTION>, Function<RankingExample<SHARED, ACTION, OUTCOME>, String>> {
    private static final ObjectMapper OM = new ObjectMapper();
    @Override
    public Function<RankingExample<SHARED, ACTION, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, Ranker<SHARED, ACTION> ranker) {
        return ex -> {
            Int2DoubleMap sparseActionIdxToReward = toSparseActionIdxToReward(rewardFunction, ex.getOutcomes());
            Map<Integer, Double> sparseRankToReward = new HashMap<>();
            var decisions = ranker.apply(ex.getRankingRequest());
            for (int i = 0; i < decisions.size(); i++) {
                var decision = decisions.get(i);
                var actionIdx = decision.getActionIndex();
                var reward = sparseActionIdxToReward.get(actionIdx);
                if (reward != 0.0){
                    sparseRankToReward.put(i, reward);
                }
            }
            try {
                return OM.writeValueAsString(sparseRankToReward);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Int2DoubleMap toSparseActionIdxToReward(RewardFunction<OUTCOME> rewardFunction, List<? extends RankingOutcome<OUTCOME>> outcomes) {
        Int2DoubleMap ret = new Int2DoubleOpenHashMap();
        for (int i = 0; i < outcomes.size(); i++) {
            var outcome = outcomes.get(i);
            var reward = rewardFunction.applyAsDouble(outcome.getOutcome());
            if(reward != 0.0){
                ret.put(outcome.getRankingDecision().getActionIndex(), reward);
            }
        }
        return ret;
    }
}
