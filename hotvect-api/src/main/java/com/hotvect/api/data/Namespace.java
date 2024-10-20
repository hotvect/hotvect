package com.hotvect.api.data;

import java.io.Serializable;

/**
 * Shared interface for {@link RawNamespace} and
 * {@link FeatureNamespace}
 * TODO let it extend Constable when we upgrade to java12 and above
 */
public interface Namespace extends Serializable {
    default String getName() {
        return toString();
    }
}
