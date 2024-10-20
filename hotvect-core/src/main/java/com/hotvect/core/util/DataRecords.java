package com.hotvect.core.util;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.SparseVector;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Deprecated(forRemoval = true)
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
            case SPARSE_VECTOR:
            case DENSE_VECTOR:
            case CATEGORICALS_TO_NUMERICALS: {
                SparseVector vector = value.getSparseVector();
                int[] names = vector.getNumericalIndices();
                double[] values = vector.getNumericalValues();
                ImmutableMap.Builder<String, Double> ret = ImmutableMap.builder();
                for (int i = 0; i < names.length; i++) {
                    ret.put(String.valueOf(names[i]), values[i]);
                }
                return ret.build();
            }
            case STRINGS_TO_NUMERICALS:
                String[] names = value.getStrings();
                double[] values = value.getNumericals();
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
