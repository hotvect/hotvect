package com.eshioji.hotvect.api.data;

/**
 * TODO consider retiring
 * Namespace interface for {@link RawValue}.
 */
@Deprecated
public interface RawNamespace extends Namespace {
    @Override
    RawValueType getValueType();
}
