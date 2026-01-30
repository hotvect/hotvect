package com.hotvect.api.transformation;

import com.hotvect.api.data.Namespace;

/**
 * Represents an object from which computations can be yielded.
 */
@Deprecated(forRemoval = true)
public interface Computable {
    /**
     * Get the computation result for the given computation id. The computation ID is specified as the string representation of the ID.
     * This method is very slow, and hence it should only be used for debugging purposes. For production, use {@link #compute(Namespace)}
     * @param computationId
     * @return
     * @param <V>
     */
    <V> V debugCompute(String computationId);

    /**
     * Get the computation result for the given computation id. The computation ID is specified as {@link Namespace} objects.
     * @param computationId
     * @return
     * @param <V>
     */
    <V> V compute(Namespace computationId);

}
