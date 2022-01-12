package com.eshioji.hotvect.api.codec.scoring;

import com.eshioji.hotvect.api.codec.common.ExampleDecoder;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;

import java.util.List;
import java.util.function.Function;

public interface ScoringExampleDecoder<RECORD, OUTCOME> extends ExampleDecoder<ScoringExample<RECORD, OUTCOME>> {
}
