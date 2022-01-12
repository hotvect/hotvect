package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.RawValue;

import java.util.function.Function;

public interface SharedTransformation<SHARED> extends Function<SHARED, RawValue> {
}
