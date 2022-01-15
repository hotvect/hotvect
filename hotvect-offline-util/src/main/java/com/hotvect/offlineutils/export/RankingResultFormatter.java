package com.hotvect.offlineutils.export;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public class RankingResultFormatter<SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, Ranker<SHARED, ACTION>, Function<RankingExample<SHARED, ACTION, OUTCOME>, String>> {
    private static final ObjectMapper OM = new ObjectMapper();
    @Override
    public Function<RankingExample<SHARED, ACTION, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, Ranker<SHARED, ACTION> ranker) {
        return ex -> {
            Int2DoubleMap sparseActionIdxToReward = toSparseActionIdxToReward(rewardFunction, ex.getOutcomes());
            ObjectNode root = OM.createObjectNode();
            if(ex.getExampleId() != null) {
                root.put("example_id", ex.getExampleId());
            }

            ArrayNode sparseRankToReward = OM.createArrayNode();
            var decisions = ranker.apply(ex.getRankingRequest());
            for (int i = 0; i < decisions.size(); i++) {
                var decision = decisions.get(i);
                var actionIdx = decision.getActionIndex();
                var reward = sparseActionIdxToReward.get(actionIdx);
                if (reward != 0.0){
                    var result = OM.createObjectNode();
                    result.put("rank", i);
                    result.put("reward", reward);
                    sparseRankToReward.add(result);
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
