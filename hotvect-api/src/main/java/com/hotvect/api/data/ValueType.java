package com.hotvect.api.data;

/**
 * Common interface for value types {@link RawValueType} and
 * {@link HashedValueType}
 */
public interface ValueType {
    boolean hasNumericValues();
    default Class<?> getJavaType(){
        // TODO default method for legacy support
        return null;
    }
}
