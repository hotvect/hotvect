package com.hotvect.onlineutils.experimentmanagement.models;

import java.util.List;
import java.util.Objects;

public record ExperimentConfiguration(
        Experiment experiment,
        List<VariantConfiguration> variants) {
    public ExperimentConfiguration {
        experiment = Objects.requireNonNull(experiment, "experiment must not be null");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants must not be null"));
    }
}
