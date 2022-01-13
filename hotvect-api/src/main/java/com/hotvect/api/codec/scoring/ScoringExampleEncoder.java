package com.hotvect.api.codec.scoring;

import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.scoring.ScoringExample;

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
