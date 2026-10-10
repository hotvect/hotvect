package com.hotvect.onlineutils.serving;

import java.util.Objects;

/** A borrowed runtime context and EMS assignments, valid while the owning offline runtime is open. */
public record SelectedAlgorithmRuntime(
        AlgorithmRuntimeContext context,
        AlgorithmSelection selection) {

    public SelectedAlgorithmRuntime {
        context = Objects.requireNonNull(context, "context must not be null");
        selection = Objects.requireNonNull(selection, "selection must not be null");
    }
}
