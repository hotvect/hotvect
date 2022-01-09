package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.Namespace;
import com.eshioji.hotvect.api.data.RawValue;

import java.util.List;
import java.util.function.BiFunction;

public interface Transformer<SHARED, ACTION, FEATURE extends Enum<FEATURE> & Namespace> extends BiFunction<SHARED, List<ACTION>, List<DataRecord<FEATURE, RawValue>>> {
    @Override
    List<DataRecord<FEATURE, RawValue>> apply(SHARED shared, List<ACTION> actions);
}
