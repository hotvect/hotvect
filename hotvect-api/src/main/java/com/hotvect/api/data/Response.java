package com.hotvect.api.data;

import java.util.List;
import java.util.Map;

public interface Response<ACTION> {
    Map<String, Object> additionalProperties();
    List<? extends Decision<ACTION>> decisions();
    FeatureStoreResponseContainer featureStoreResponseContainer();
}
