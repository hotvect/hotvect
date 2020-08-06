package com.eshioji.hotvect.api.data;

/**
 * Common interface for value types {@link com.eshioji.hotvect.api.data.raw.RawValueType} and
 * {@link com.eshioji.hotvect.api.data.hashed.HashedValueType}
 */
public interface ValueType {
    boolean hasNumericValues();
}
