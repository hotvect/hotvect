package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.util.IdentityCaching;

import java.util.function.Function;

public interface ActionTransformation<ACTION> extends Function<ACTION, RawValue> {
}
