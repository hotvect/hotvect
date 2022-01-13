package com.hotvect.api.codec.scoring;

import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.scoring.ScoringExample;

public interface ScoringExampleDecoder<RECORD, OUTCOME> extends ExampleDecoder<ScoringExample<RECORD, OUTCOME>> {
}
