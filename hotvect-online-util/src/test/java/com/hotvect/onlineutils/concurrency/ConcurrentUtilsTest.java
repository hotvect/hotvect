package com.hotvect.onlineutils.concurrency;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrentUtilsTest {

    @Test
    void getBatchSizeDefaultsToThirtyTwoWhenBatchSizeIsMissing() {
        assertEquals(32, ConcurrentUtils.getBatchSize(Optional.empty()));
    }

    @Test
    void getBatchSizeDefaultsToThirtyTwoWhenBatchSizeIsNonPositive() {
        assertEquals(32, ConcurrentUtils.getBatchSize(Optional.of(0)));
        assertEquals(32, ConcurrentUtils.getBatchSize(Optional.of(-1)));
    }

    @Test
    void getBatchSizePreservesExplicitPositiveValue() {
        assertEquals(64, ConcurrentUtils.getBatchSize(Optional.of(64)));
    }
}
