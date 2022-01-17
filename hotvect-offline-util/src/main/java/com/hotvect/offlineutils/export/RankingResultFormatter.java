package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public class RankingResultFormatter<SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, Ranker<SHARED, ACTION>, Function<RankingExample<SHARED, ACTION, OUTCOME>, String>> {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Function<RankingExample<SHARED, ACTION, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, Ranker<SHARED, ACTION> ranker) {
        return ex -> {
            Int2DoubleMap sparseActionIdxToReward = toSparseActionIdxToReward(rewardFunction, ex.getOutcomes());
            ObjectNode root = objectMapper.createObjectNode();
            root.put("example_id", ex.getExampleId());

            ArrayNode rankToReward = objectMapper.createArrayNode();
            var decisions = ranker.apply(ex.getRankingRequest());
            for (int i = 0; i < decisions.size(); i++) {
                var decision = decisions.get(i);
                var actionIdx = decision.getActionIndex();
                var score = decision.getScore();
                var reward = sparseActionIdxToReward.get(actionIdx);
                var result = objectMapper.createObjectNode();
                result.put("rank", i);
                if (score != null) {
                    result.put("score", score);
                }
                result.put("reward", reward);
                rankToReward.add(result);
            }
            root.set("result", rankToReward);
            try {
                return objectMapper.writeValueAsString(root);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Int2DoubleMap toSparseActionIdxToReward(RewardFunction<OUTCOME> rewardFunction, List<? extends RankingOutcome<OUTCOME>> outcomes) {
        Int2DoubleMap ret = new Int2DoubleOpenHashMap();
        for (RankingOutcome<OUTCOME> outcome : outcomes) {
            var reward = rewardFunction.applyAsDouble(outcome.getOutcome());
            if (reward != 0.0) {
                ret.put(outcome.getRankingDecision().getActionIndex(), reward);
            }
        }
        return ret;
    }
}
