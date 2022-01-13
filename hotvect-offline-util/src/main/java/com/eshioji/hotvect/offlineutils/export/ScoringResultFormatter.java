package com.eshioji.hotvect.offlineutils.export;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.data.scoring.ScoringExample;

import java.util.function.BiFunction;
import java.util.function.Function;

public class ScoringResultFormatter<RECORD, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, Scorer<RECORD>, Function<ScoringExample<RECORD, OUTCOME>, String>> {
    @Override
    public Function<ScoringExample<RECORD, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, Scorer<RECORD> scorer) {
        return ex -> {
            var reward = rewardFunction.applyAsDouble(ex.getOutcome());
            var score = scorer.applyAsDouble(ex.getRecord());
            var exampleId = ex.getExampleId();
            var ret = new StringBuilder();
            if(exampleId != null){
                ret.append(exampleId);
                ret.append(',');
            }
            ret.append(score);
            ret.append(',');
            ret.append(reward);
            return ret.toString();
        };
    }
}
