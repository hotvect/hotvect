package com.hotvect.core.annotation.processor.model;

import java.util.Objects;

public record FeatureSpec(String name, String type) {
    public FeatureSpec {
        Objects.requireNonNull(name, "name cannot be null");
    }
}
