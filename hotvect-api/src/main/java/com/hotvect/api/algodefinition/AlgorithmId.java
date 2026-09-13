package com.hotvect.api.algodefinition;

/** Identifies one published algorithm code version. */
public record AlgorithmId(String algorithmName, String algorithmVersion) {

    /** Validates the structured published identity fields. */
    public AlgorithmId {
        algorithmName = requireNonBlank(algorithmName, "algorithmName");
        algorithmVersion = requireNonBlank(algorithmVersion, "algorithmVersion");
    }

    /** Returns an opaque printable form of this published identity. */
    public String value() {
        return algorithmName + "@" + algorithmVersion;
    }

    @Override
    public String toString() {
        return value();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
