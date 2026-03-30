package com.hotvect.api.codec.ranking;

import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * TODO
 */
public interface RankingExampleEncoder<SHARED, ACTION, OUTCOME> extends ExampleEncoder<RankingExample<SHARED, ACTION, OUTCOME>> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    ByteBuffer apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode);
}
