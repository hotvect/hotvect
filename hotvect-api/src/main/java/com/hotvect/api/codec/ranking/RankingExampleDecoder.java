package com.hotvect.api.codec.ranking;

import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.ranking.RankingExample;

import java.util.List;

public interface RankingExampleDecoder<SHARED, ACTION, OUTCOME> extends ExampleDecoder<RankingExample<SHARED, ACTION, OUTCOME>> {
    @Override
    List<RankingExample<SHARED, ACTION, OUTCOME>> apply(String toDecode);
}
