package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.core.audit.AuditableScoringVectorizer;
import com.hotvect.core.audit.RawFeatureName;

import java.util.List;
import java.util.Map;

public class RankingExampleJsonEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {

    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        throw new AssertionError("not implemented");
    }
}
