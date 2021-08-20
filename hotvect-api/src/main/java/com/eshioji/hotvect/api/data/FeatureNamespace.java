package com.eshioji.hotvect.api.data;

import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.hashed.HashedValueType;

/**
 * Namespace interface for {@link HashedValue}.
 */
public interface FeatureNamespace extends Namespace {
    @Override
    HashedValueType getValueType();
}
