package com.hotvect.api.transformation.memoization;

import com.hotvect.api.data.Namespace;

/**
 * Represents an object from which computations can be yielded.
 */
public interface Computable {
    /**
     * Get the computation result for the given computation id. The computation ID is specified as the string representation of the ID.
     * This method is very slow, and hence it should only be used for debugging purposes. For production, use {@link #computeIfAbsent(Namespace)}
     * @param computationId
     * @return
     * @param <V>
     */
    <V> V debugComputeIfAbsent(String computationId);

    /**
     * Get the computation result for the given computation id. The computation ID is specified as {@link Namespace} objects.
     * @param computationId
     * @return
     * @param <V>
     */
    <V> V computeIfAbsent(Namespace computationId);

}
