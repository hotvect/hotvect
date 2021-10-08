package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.Namespace;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

import static com.eshioji.hotvect.core.util.DataRecords.pojonize;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * A {@link ExampleEncoder} that encodes a {@link DataRecord} into a JSON String
 * @param <K>
 */
public class JsonRecordEncoder implements Function<DataRecord<?, RawValue>, String> {
    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public String apply(DataRecord<?, RawValue> toEncode) {
        try {
            return OM.writeValueAsString(pojonize(toEncode));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }


}
