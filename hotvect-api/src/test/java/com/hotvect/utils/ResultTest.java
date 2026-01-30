package com.hotvect.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultTest {

    @Test
    void testSuccess() {
        Result.Success<Integer, String> success = new Result.Success<>(42);

        // Verify value
        assertEquals(42, success.value());
    }

    @Test
    void testFailure() {
        Result.Failure<Integer, String> failure = new Result.Failure<>("Error occurred");

        // Verify error message
        assertEquals("Error occurred", failure.error());
    }

    @Test
    void testPolymorphism() {
        Result<Integer, String> success = new Result.Success<>(42);
        Result<Integer, String> failure = new Result.Failure<>("Error occurred");

        // Perform action based on type
        if (success instanceof Result.Success<Integer, String> s) {
            assertEquals(42, s.value());
        }

        if (failure instanceof Result.Failure<Integer, String> f) {
            assertEquals("Error occurred", f.error());
        }
    }
}
