package com.hotvect.api.transformation.ranking;

import com.hotvect.api.data.RawValue;

import java.util.function.Function;

public interface SharedTransformation<SHARED> extends Function<SHARED, RawValue> {
}
