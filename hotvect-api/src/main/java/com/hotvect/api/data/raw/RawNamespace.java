package com.hotvect.api.data.raw;

import com.hotvect.api.data.Namespace;

/**
 * TODO consider retiring
 * Namespace interface for {@link RawValue}.
 */
@Deprecated
public interface RawNamespace extends Namespace {
    @Override
    RawValueType getValueType();
}
