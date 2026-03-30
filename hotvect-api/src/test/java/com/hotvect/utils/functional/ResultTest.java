package com.hotvect.utils.functional;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void testSuccess() {
        Result<Integer> success = Result.success(42);
        assertTrue(success.isSuccess());
        assertEquals(42, success.getValue());
        assertNull(success.getError());
    }

    @Test
    void testVoidSuccess() {
        Result<Void> success = Result.success();
        assertTrue(success.isSuccess());
        assertNull(success.getValue());
        assertNull(success.getError());
    }

    @Test
    void testFailure() {
        RuntimeException error = new RuntimeException("boom");
        Result<Integer> failure = Result.failure(error);
        assertFalse(failure.isSuccess());
        assertSame(error, failure.getError());
        assertThrows(NoSuchElementException.class, failure::getValue);
    }
}
