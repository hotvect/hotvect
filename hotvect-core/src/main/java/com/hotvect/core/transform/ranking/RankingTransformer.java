package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;

import java.util.List;
import java.util.function.BiFunction;

public interface RankingTransformer<SHARED, ACTION, FEATURE extends Enum<FEATURE> & FeatureNamespace> extends BiFunction<SHARED, List<ACTION>, List<DataRecord<FEATURE, RawValue>>> {
    @Override
    List<DataRecord<FEATURE, RawValue>> apply(SHARED shared, List<ACTION> actions);
}
