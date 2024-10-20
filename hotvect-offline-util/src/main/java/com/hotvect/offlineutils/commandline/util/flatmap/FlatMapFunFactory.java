package com.hotvect.offlineutils.commandline.util.flatmap;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface FlatMapFunFactory extends Function<Optional<JsonNode>, Function<String, List<String>>> {
    @Override
    Function<String, List<String>> apply(Optional<JsonNode> hyperparameter);
}
