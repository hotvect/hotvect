package com.hotvect.onlineutils.experimentmanagement.models;

import java.util.List;
import java.util.Objects;

public record Experiment(
        int experimentId,
        String experimentName,
        List<Variant> variants,
        int rampUpPercentage,
        List<Shard> shards) {
    public Experiment {
        variants = List.copyOf(Objects.requireNonNull(variants, "variants must not be null"));
        shards = List.copyOf(Objects.requireNonNull(shards, "shards must not be null"));
    }
}
