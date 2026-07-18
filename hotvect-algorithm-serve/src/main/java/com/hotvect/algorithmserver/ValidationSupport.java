package com.hotvect.algorithmserver;

public final class ValidationSupport {
    private ValidationSupport() {
    }

    public static void requireArgument(boolean condition, String messageTemplate, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException(String.format(messageTemplate, args));
        }
    }
}
