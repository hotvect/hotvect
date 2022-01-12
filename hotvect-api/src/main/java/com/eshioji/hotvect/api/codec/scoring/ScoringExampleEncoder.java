package com.eshioji.hotvect.api.codec.scoring;

import com.eshioji.hotvect.api.codec.common.ExampleEncoder;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;

import java.util.function.Function;

/**
 * TODO
 */
public interface ScoringExampleEncoder<RECORD, OUTCOME> extends ExampleEncoder<ScoringExample<RECORD, OUTCOME>> {
    /**
     * @param toEncode record to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(ScoringExample<RECORD, OUTCOME> toEncode);
}
