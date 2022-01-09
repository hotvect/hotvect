package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.RawValue;

import java.util.function.BiFunction;

public interface Transformation<SHARED, ACTION>  extends BiFunction<SHARED, ACTION, RawValue> {

}
