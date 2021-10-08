package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.google.common.collect.ImmutableMap;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class DataRecords {
    private DataRecords(){}

    public static Map<String, Object> pojonize(DataRecord<?, RawValue> input) {
        return input.asEnumMap().entrySet().stream().map(x -> {
            String k = x.getKey().toString();
            Object value = pojonize(x.getValue());
            return new AbstractMap.SimpleEntry<>(k, value);
        }).collect(toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
    }

    private static Object pojonize(RawValue value) {
        switch (value.getValueType()) {
            case SINGLE_STRING:
                return value.getSingleString();
            case STRINGS:
                return Arrays.asList(value.getStrings());
            case SINGLE_CATEGORICAL:
                return value.getSingleCategorical();
            case CATEGORICALS:
                return Arrays.stream(value.getCategoricals()).boxed().collect(toList());
            case SINGLE_NUMERICAL:
                return value.getSingleNumerical();
            case CATEGORICALS_TO_NUMERICALS: {
                var vector = value.getCategoricalsToNumericals();
                var names = vector.indices();
                var values = vector.values();
                ImmutableMap.Builder<String, Double> ret = ImmutableMap.builder();
                for (int i = 0; i < vector.size(); i++) {
                    ret.put(String.valueOf(names[i]), values[i]);
                }
                return ret.build();
            }
            case STRINGS_TO_NUMERICALS:
                var names = value.getStrings();
                var values = value.getNumericals();
                ImmutableMap.Builder<String, Double> ret = ImmutableMap.builder();
                for (int i = 0; i < names.length; i++) {
                    ret.put(String.valueOf(names[i]), values[i]);
                }
                return ret.build();
            default:
                throw new AssertionError();
        }
    }

}
