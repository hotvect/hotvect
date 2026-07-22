package com.hotvect.core.annotation.backend;

/**
 * Result of {@link GeneratedTransformerBackend#resolve}. Exactly one of {@code initializerExpression} or {@code error}
 * is set: a successful resolution carries the Java source expression that constructs the feature
 * {@code ValueType}; a failed resolution carries an error message that the processor reports as a compile error.
 */
public record Resolution(String initializerExpression, String error) {
    public Resolution {
        boolean hasExpression = initializerExpression != null && !initializerExpression.isBlank();
        boolean hasError = error != null && !error.isBlank();
        if (hasExpression == hasError) {
            throw new IllegalArgumentException("Exactly one of initializerExpression or error must be set");
        }
    }

    public static Resolution of(String initializerExpression) {
        return new Resolution(initializerExpression, null);
    }

    public static Resolution error(String error) {
        return new Resolution(null, error);
    }

    public boolean isError() {
        return error != null;
    }
}
