package com.hotvect.core.transform;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;

/**
 * Represents an object from which computations can be yielded.
 */
public interface Computable<ARGUMENT> {
    /**
     * Get the computation result for the given computation id. The computation ID is specified as the string representation of the ID.
     * This method is very slow, and hence it should only be used for debugging purposes. For production, use {@link #compute(Namespace)}
     *
     * @param computationId
     * @param <V>
     * @return
     */
    <V> V debugCompute(String computationId);

    /**
     * Get the computation result for the given computation id. The computation ID is specified as {@link Namespace} objects.
     *
     * @param computationId
     * @param <V>
     * @return
     */
    <V> V compute(Namespace computationId);

    ARGUMENT getOriginalInput();

    Computing<ARGUMENT> appendComputations(
            Mapping<Namespace, Computation<ARGUMENT, Object>> memoized,
            Mapping<Namespace, Computation<ARGUMENT, Object>> onDemand
    );
}