package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;
import com.eshioji.hotvect.api.codec.scoring.ScoringExampleEncoder;
import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.transform.regression.ScoringTransformer;
import com.eshioji.hotvect.core.util.DataRecords;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class ScoringExampleJsonEncoder<RECORD, OUTCOME> implements ScoringExampleEncoder<RECORD, OUTCOME> {
    private static final ObjectMapper OM = new ObjectMapper();
    private final ScoringTransformer<RECORD, ?> scoringTransformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public ScoringExampleJsonEncoder(ScoringTransformer<RECORD, ?> scoringTransformer, RewardFunction<OUTCOME> rewardFunction) {
        this.scoringTransformer = scoringTransformer;
        this.rewardFunction = rewardFunction;
    }

    @Override
    public String apply(ScoringExample<RECORD, OUTCOME> toEncode) {
        double target = rewardFunction.applyAsDouble(toEncode.getOutcome());
        DataRecord<?, RawValue> transformed = this.scoringTransformer.apply(toEncode.getRecord());

        Map<String, Object> pojonized = DataRecords.pojonize(transformed);
        pojonized.put("target", target);

        try {
            return OM.writeValueAsString(pojonized);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
