package com.hotvect.utils;

import java.util.NoSuchElementException;

public sealed interface Result<VALUE, ERROR> permits Result.Failure, Result.Success {
    record Failure<VALUE, ERROR>(ERROR error) implements Result<VALUE, ERROR> {
        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public VALUE getValue() {
            throw new NoSuchElementException(error.toString());
        }

        @Override
        public ERROR getError() {
            return error;
        }
    }
    record Success<VALUE, ERROR>(VALUE value) implements Result<VALUE, ERROR> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public VALUE getValue() {
            return value;
        }

        @Override
        public ERROR getError() {
            return null;
        }
    }

    static <VALUE, ERROR> Result<VALUE, ERROR> success(VALUE value) {
        return new Success<>(value);
    }

    static <VALUE, ERROR> Result<VALUE, ERROR> failure(ERROR error) {
        return new Failure<>(error);
    }

    boolean isSuccess();

    VALUE getValue();

    ERROR getError();
}

