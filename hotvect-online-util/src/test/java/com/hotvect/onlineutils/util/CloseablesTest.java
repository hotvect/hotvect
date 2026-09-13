package com.hotvect.onlineutils.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CloseablesTest {

    @Test
    void closesEveryResourceAndPreservesTheFirstFailure() {
        List<String> closed = new ArrayList<>();
        RuntimeException first = new RuntimeException("first");
        IllegalStateException second = new IllegalStateException("second");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Closeables.closeAll(
                "close failed",
                failing(closed, "first", first),
                succeeding(closed, "middle"),
                failing(closed, "last", second)));

        assertSame(first, thrown);
        assertEquals(List.of("first", "middle", "last"), closed);
        assertEquals(List.of(second), List.of(thrown.getSuppressed()));
    }

    @Test
    void suppressesCloseFailuresOntoAnExistingFailure() {
        RuntimeException operationFailure = new RuntimeException("operation");
        IllegalStateException closeFailure = new IllegalStateException("close");

        Closeables.closeAfterFailure(
                operationFailure,
                failing(new ArrayList<>(), "resource", closeFailure));

        assertEquals(List.of(closeFailure), List.of(operationFailure.getSuppressed()));
    }

    @Test
    void wrapsACheckedCloseFailure() {
        Exception closeFailure = new Exception("close");

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> Closeables.closeAll("close failed", () -> {
                    throw closeFailure;
                }));

        assertEquals("close failed", thrown.getMessage());
        assertSame(closeFailure, thrown.getCause());
    }

    private static AutoCloseable succeeding(List<String> closed, String name) {
        return () -> closed.add(name);
    }

    private static AutoCloseable failing(List<String> closed, String name, RuntimeException failure) {
        return () -> {
            closed.add(name);
            throw failure;
        };
    }
}
