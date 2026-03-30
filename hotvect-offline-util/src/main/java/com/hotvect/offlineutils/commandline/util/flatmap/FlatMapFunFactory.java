package com.hotvect.offlineutils.commandline.util.flatmap;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface FlatMapFunFactory extends Function<Optional<JsonNode>, Function<String, List<ByteBuffer>>> {
    @Override
    Function<String, List<ByteBuffer>> apply(Optional<JsonNode> hyperparameter);
}
