package com.hotvect.example.product;

import java.util.Objects;

public record ProductQuery(String query, String preferredCategory, double budget) {
    public ProductQuery {
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(preferredCategory, "preferredCategory cannot be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query cannot be blank");
        }
        if (!hasAsciiAlphanumeric(query)) {
            throw new IllegalArgumentException("query must contain an ASCII letter or digit");
        }
        if (preferredCategory.isBlank()) {
            throw new IllegalArgumentException("preferredCategory cannot be blank");
        }
        if (!hasAsciiAlphanumeric(preferredCategory)) {
            throw new IllegalArgumentException("preferredCategory must contain an ASCII letter or digit");
        }
        if (!Double.isFinite(budget) || budget <= 0.0) {
            throw new IllegalArgumentException("budget must be positive");
        }
    }

    private static boolean hasAsciiAlphanumeric(String value) {
        return value.chars().anyMatch(character ->
                (character >= 'a' && character <= 'z')
                        || (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9')
        );
    }
}
