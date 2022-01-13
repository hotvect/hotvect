package com.hotvect.api.data;

import com.hotvect.api.data.hashed.HashedValueType;
import com.hotvect.api.data.raw.RawValueType;

/**
 * Common interface for value types {@link RawValueType} and
 * {@link HashedValueType}
 */
public interface ValueType {
    boolean hasNumericValues();
}
