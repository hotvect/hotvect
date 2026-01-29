package com.hotvect.api.data;

import com.google.common.annotations.Beta;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Shared interface for {@link RawNamespace} and
 * {@link FeatureNamespace}
 * TODO let it extend Constable when we upgrade to java12 and above
 */
public interface Namespace extends Serializable {
    default String getName() {
        return toString();
    }

    default Namespace[] getComponents() {
        return new Namespace[]{this};
    }

    /**
     * Not performance optimized, do not use in hot path
     * @return
     */
    @Beta
    default Class<?> getReturnTypeHint() {
        ValueType valueType = getFeatureValueType();
        if(valueType != null){
            return valueType.getJavaType();
        } else {
            return null;
        }
    }
    static <V extends Namespace> Comparator<V> alphabetical(){
        return Comparator.comparing(Object::toString);
    }
    default ValueType getFeatureValueType(){
        return null;
    }
}
