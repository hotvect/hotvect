package com.hotvect.onlineutils.serving;

import java.util.Objects;

/** The result and immutable serving attribution produced by one runtime-owned invocation. */
public record AlgorithmExecution<RESULT>(
        RESULT result,
        AlgorithmSelection selection) {

    public AlgorithmExecution {
        result = Objects.requireNonNull(result, "result must not be null");
        selection = Objects.requireNonNull(selection, "selection must not be null");
    }
}
