package com.hotvect.api.state;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface StateGenerator<S extends State> extends Function<Map<String, List<File>>, S> {
    @Override
    S apply(Map<String, List<File>> sourceFiles);
}
