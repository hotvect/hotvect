package com.hotvect.onlineutils.serving;

import java.time.Instant;
import java.util.Objects;

/** A rejected complete-refresh snapshot, expressed without graph implementation details. */
public record ServingRefreshFailure(Instant failedAt, String summary) {

    /** Validates one operational failure. */
    public ServingRefreshFailure {
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
    }
}
