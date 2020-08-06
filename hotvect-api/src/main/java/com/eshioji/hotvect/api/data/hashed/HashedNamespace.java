package com.eshioji.hotvect.api.data.hashed;

import com.eshioji.hotvect.api.data.Namespace;

/**
 * Namespace interface for {@link HashedValue}.
 */
public interface HashedNamespace extends Namespace {
    @Override
    HashedValueType getValueType();
}
