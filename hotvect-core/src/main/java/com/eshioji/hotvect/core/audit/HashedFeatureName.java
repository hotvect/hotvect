package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.data.FeatureNamespace;

import java.util.Objects;

public class HashedFeatureName {
    private final FeatureNamespace featureNamespace;
    private final int featureName;

    public HashedFeatureName(FeatureNamespace featureNamespace, int featureName) {
        this.featureNamespace = featureNamespace;
        this.featureName = featureName;
    }

    public FeatureNamespace getFeatureNamespace() {
        return featureNamespace;
    }

    public int getFeatureName() {
        return featureName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HashedFeatureName that = (HashedFeatureName) o;
        return featureName == that.featureName && featureNamespace.equals(that.featureNamespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(featureNamespace, featureName);
    }
}
