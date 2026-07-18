package com.hotvect.example.product;

import java.util.Objects;

public record Product(
        String title,
        String category,
        double price,
        double popularity,
        double novelty
) {
    public Product {
        Objects.requireNonNull(title, "title cannot be null");
        Objects.requireNonNull(category, "category cannot be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title cannot be blank");
        }
        if (!hasAsciiAlphanumeric(title)) {
            throw new IllegalArgumentException("title must contain an ASCII letter or digit");
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("category cannot be blank");
        }
        if (!hasAsciiAlphanumeric(category)) {
            throw new IllegalArgumentException("category must contain an ASCII letter or digit");
        }
        if (!Double.isFinite(price) || price < 0.0) {
            throw new IllegalArgumentException("price must be finite and non-negative");
        }
        requireUnitInterval(popularity, "popularity");
        requireUnitInterval(novelty, "novelty");
    }

    private static void requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
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
