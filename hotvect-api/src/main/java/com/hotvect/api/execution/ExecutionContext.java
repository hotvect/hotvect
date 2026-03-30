package com.hotvect.api.execution;

import java.util.Objects;

public record ExecutionContext(WorkloadMode workloadMode, InputSemantic inputSemantic) {

    public ExecutionContext {
        Objects.requireNonNull(workloadMode, "workloadMode");
        Objects.requireNonNull(inputSemantic, "inputSemantic");
    }

    public static ExecutionContext of(WorkloadMode workloadMode, InputSemantic inputSemantic) {
        return new ExecutionContext(workloadMode, inputSemantic);
    }

    public static ExecutionContext realtime(InputSemantic inputSemantic) {
        return of(WorkloadMode.REALTIME, inputSemantic);
    }

    public static ExecutionContext batch(InputSemantic inputSemantic) {
        return of(WorkloadMode.BATCH, inputSemantic);
    }
}
