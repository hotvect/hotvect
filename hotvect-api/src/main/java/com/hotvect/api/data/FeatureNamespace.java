package com.hotvect.api.data;

import java.util.Comparator;

/**
 * Namespace interface for {@link HashedValue}.
 */
public interface FeatureNamespace extends Namespace {
    ValueType getFeatureValueType();
    static <V extends FeatureNamespace> Comparator<V> alphabetical(){
        return Comparator.comparing(Object::toString);
    }
}
