package com.hotvect.api.algodefinition.state;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public interface StateGenerator extends BiFunction<Map<String, List<File>>, File, Map<String, Object>> {
    /**
     *
     * @param sourceFiles the source data to be used as input
     * @param destFile the destination file to which the generated state should be written to
     * @return optional generator-specific metadata. This may be {@code null} or immutable; Hotvect copies any
     *     supplied entries before adding its task metadata.
     */
    @Override
    Map<String, Object> apply(Map<String, List<File>> sourceFiles, File destFile);
}
