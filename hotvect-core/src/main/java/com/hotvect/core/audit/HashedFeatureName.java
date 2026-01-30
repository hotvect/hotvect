package com.hotvect.core.audit;

import com.hotvect.api.data.FeatureNamespace;

@Deprecated(forRemoval = true)
public record HashedFeatureName(FeatureNamespace featureNamespace, int featureName) {
}
