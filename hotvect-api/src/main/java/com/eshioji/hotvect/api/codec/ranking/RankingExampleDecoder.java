package com.eshioji.hotvect.api.codec.ranking;

import com.eshioji.hotvect.api.codec.common.ExampleDecoder;
import com.eshioji.hotvect.api.data.ranking.RankingExample;

import java.util.List;
import java.util.function.Function;

public interface RankingExampleDecoder<SHARED, ACTION, OUTCOME> extends ExampleDecoder<RankingExample<SHARED, ACTION, OUTCOME>> {
    @Override
    List<RankingExample<SHARED, ACTION, OUTCOME>> apply(String toDecode);
}
