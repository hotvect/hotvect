package com.hotvect.utils.functional;

import java.util.NoSuchElementException;

/**
 * A Kotlin-style result type: success contains a value, failure contains a {@link Throwable}.
 */
public sealed interface Result<VALUE> permits Result.Failure, Result.Success {
    record Failure<VALUE>(Throwable error) implements Result<VALUE> {
        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public VALUE getValue() {
            throw new NoSuchElementException(error.toString());
        }

        @Override
        public Throwable getError() {
            return error;
        }
    }

    record Success<VALUE>(VALUE value) implements Result<VALUE> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public VALUE getValue() {
            return value;
        }

        @Override
        public Throwable getError() {
            return null;
        }
    }

    static <VALUE> Result<VALUE> success(VALUE value) {
        return new Success<>(value);
    }

    static Result<Void> success() {
        return new Success<>(null);
    }

    static <VALUE> Result<VALUE> failure(Throwable error) {
        return new Failure<>(error);
    }

    boolean isSuccess();

    VALUE getValue();

    Throwable getError();
}
