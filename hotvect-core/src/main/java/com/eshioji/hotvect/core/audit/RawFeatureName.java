package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.data.FeatureNamespace;

import java.util.Objects;

public class RawFeatureName {
    private final FeatureNamespace featureNamespace;
    private final String sourceRawValue;

    public RawFeatureName(FeatureNamespace featureNamespace, String sourceRawValue) {
        this.featureNamespace = featureNamespace;
        this.sourceRawValue = sourceRawValue;
    }

    public FeatureNamespace getFeatureNamespace() {
        return featureNamespace;
    }

    public String getSourceRawValue() {
        return sourceRawValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RawFeatureName that = (RawFeatureName) o;
        return featureNamespace.equals(that.featureNamespace) && sourceRawValue.equals(that.sourceRawValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(featureNamespace, sourceRawValue);
    }
}
