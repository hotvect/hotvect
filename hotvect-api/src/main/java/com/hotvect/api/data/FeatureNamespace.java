package com.hotvect.api.data;

/**
 * Namespace interface for {@link HashedValue}.
 */
public interface FeatureNamespace extends Namespace {
    @Override
    HashedValueType getValueType();
}
