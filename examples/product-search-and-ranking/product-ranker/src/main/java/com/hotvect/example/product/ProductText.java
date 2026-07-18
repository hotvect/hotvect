package com.hotvect.example.product;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class ProductText {
    private ProductText() {
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    static Set<String> tokens(String value) {
        return Arrays.stream(normalize(value).split(" +"))
                .filter(token -> !token.isBlank())
                .map(ProductText::stem)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String stem(String token) {
        if (token.length() > 5 && token.endsWith("ing")) {
            return token.substring(0, token.length() - 3);
        }
        if (token.length() > 4 && token.endsWith("er")) {
            return token.substring(0, token.length() - 2);
        }
        return token;
    }
}
