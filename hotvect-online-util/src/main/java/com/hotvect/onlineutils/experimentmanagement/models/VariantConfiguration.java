package com.hotvect.onlineutils.experimentmanagement.models;

import com.hotvect.api.algodefinition.AlgorithmInstance;

public record VariantConfiguration(
        Variant variant,
        AlgorithmInstance<?> algorithmInstance) {
}
