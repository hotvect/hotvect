package com.hotvect.api.data;

import com.hotvect.api.data.raw.RawNamespace;

/**
 * TODO consider retiring
 * Shared interface for {@link RawNamespace} and
 * {@link FeatureNamespace}
 */
public interface Namespace {
    ValueType getValueType();
}
