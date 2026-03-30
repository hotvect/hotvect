package com.hotvect.onlineutils.concurrency.fileutils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertSame;

class UnorderedMultiFileReaderErrorPriorityTest {

    @Test
    void reportErrorPrefersRealFailureOverInterruptedPlaceholder() {
        UnorderedMultiFileReader.ReadState<String> state =
                new UnorderedMultiFileReader.ReadState<>(new LinkedBlockingQueue<>());

        RuntimeException interrupted = new RuntimeException(new InterruptedException());
        RuntimeException realError = new RuntimeException("boom");

        state.reportError(interrupted);
        state.reportError(realError);

        assertSame(realError, state.getError());
    }

    @Test
    void reportErrorKeepsExistingRealFailureWhenLaterInterruptedPlaceholderArrives() {
        UnorderedMultiFileReader.ReadState<String> state =
                new UnorderedMultiFileReader.ReadState<>(new LinkedBlockingQueue<>());

        RuntimeException realError = new RuntimeException("boom");
        RuntimeException interrupted = new RuntimeException(new InterruptedException());

        state.reportError(realError);
        state.reportError(interrupted);

        assertSame(realError, state.getError());
    }
}
