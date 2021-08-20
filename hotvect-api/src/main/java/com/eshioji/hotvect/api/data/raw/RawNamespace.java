package com.eshioji.hotvect.api.data.raw;

import com.eshioji.hotvect.api.data.Namespace;

/**
 * TODO consider retiring
 * Namespace interface for {@link RawValue}.
 */
public interface RawNamespace extends Namespace {
    @Override
    RawValueType getValueType();
}
