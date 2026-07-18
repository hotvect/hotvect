package com.hotvect.algorithmserver;

import java.util.Objects;
import java.util.Optional;

record RuntimeSelection(
        AlgorithmRuntime runtime,
        Optional<String> variantId,
        Optional<String> assignmentKey,
        Optional<Boolean> variantDefault,
        Optional<Boolean> variantControl,
        Optional<Integer> shardAllocationRatio
) {
    RuntimeSelection(AlgorithmRuntime runtime, Optional<String> variantId) {
        this(
                runtime,
                variantId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    RuntimeSelection {
        runtime = Objects.requireNonNull(runtime);
        variantId = variantId == null ? Optional.empty() : variantId;
        assignmentKey = assignmentKey == null ? Optional.empty() : assignmentKey;
        variantDefault = variantDefault == null ? Optional.empty() : variantDefault;
        variantControl = variantControl == null ? Optional.empty() : variantControl;
        shardAllocationRatio = shardAllocationRatio == null ? Optional.empty() : shardAllocationRatio;
    }

    static RuntimeSelection ems(
            AlgorithmRuntime runtime,
            String variantId,
            String assignmentKey,
            Boolean variantDefault,
            Boolean variantControl,
            Integer shardAllocationRatio) {
        return new RuntimeSelection(
                runtime,
                Optional.of(Objects.requireNonNull(variantId, "variantId must not be null")),
                Optional.of(Objects.requireNonNull(assignmentKey, "assignmentKey must not be null")),
                Optional.ofNullable(variantDefault),
                Optional.ofNullable(variantControl),
                Optional.ofNullable(shardAllocationRatio)
        );
    }
}
