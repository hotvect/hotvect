package com.eshioji.hotvect.api.codec.ranking;

import com.eshioji.hotvect.api.codec.common.ExampleEncoder;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;

import java.util.function.Function;

/**
 * TODO
 */
public interface RankingExampleEncoder<SHARED, ACTION, OUTCOME> extends ExampleEncoder<RankingExample<SHARED, ACTION, OUTCOME>> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode);
}
