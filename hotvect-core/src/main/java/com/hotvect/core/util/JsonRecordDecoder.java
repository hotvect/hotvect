package com.hotvect.core.util;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.raw.RawNamespace;
import com.hotvect.api.data.raw.RawValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.primitives.Ints;

import java.util.Map;

import static com.google.common.base.Preconditions.checkState;

/**
 * A {@link DataRecordDecoder} that decodes a JSON string
 * @param <R>
 */
public class JsonRecordDecoder<R extends Enum<R> & RawNamespace> implements DataRecordDecoder<R> {
    private static final ObjectMapper OM = new ObjectMapper();
    private final Class<R> keyClass;

    public JsonRecordDecoder(Class<R> keyClass) {
        this.keyClass = keyClass;
    }

    @Override
    public DataRecord<R, RawValue> apply(String serialized) {
        try {
            ObjectNode tree = (ObjectNode) OM.readTree(serialized);
            return parse(tree);
        } catch (Throwable e) {
            throw new IllegalArgumentException(String.format("Invalid input:%s", serialized), e);
        }
    }

    private DataRecord<R, RawValue> parse(ObjectNode tree) {
        DataRecord<R, RawValue> ret = new DataRecord<R, RawValue>(keyClass);

        for (R key : keyClass.getEnumConstants()) {
            JsonNode value = tree.get(key.toString());
            if (value == null || value.isNull()) {
                // No value present, skip it
                continue;
            }

            RawValue parsed = parse(key, value);
            ret.put(key, parsed);
        }

        return ret;
    }

    private RawValue parse(R key, JsonNode value) {
        switch (key.getValueType()) {
            case SINGLE_CATEGORICAL: {
                checkState(value.isInt(),
                        "Expected integer but got %s:%s", value.getNodeType(), value);
                if (value.isNull()) {
                    return null;
                }
                return RawValue.singleCategorical(value.asInt());
            }
            case CATEGORICALS: {
                checkState(value.isArray(),
                        "Expected array of integers but got %s:%s", value.getNodeType(), value);
                if (value.isNull() || value.isEmpty()) {
                    return null;
                }
                ArrayNode array = (ArrayNode) value;
                int[] names = new int[array.size()];
                int i = 0;
                for (JsonNode node : array) {
                    checkState(node.isInt(),
                            "Expected array of integers but got %s:%s", node.getNodeType(), node);
                    names[i] = node.asInt();
                    i++;
                }
                return RawValue.categoricals(names);
            }
            case SINGLE_STRING: {
                if (value.isNull()) {
                    return null;
                }
                return RawValue.singleString(value.asText());
            }
            case STRINGS: {
                checkState(value.isArray(),
                        "Expected array of strings but got %s:%s", value.getNodeType(), value);
                if (value.isNull() || value.isEmpty()) {
                    return null;
                }
                ArrayNode array = (ArrayNode) value;
                String[] names = new String[array.size()];
                int i = 0;
                for (JsonNode node : array) {
                    names[i] = node.asText();
                    i++;
                }
                return RawValue.strings(names);
            }
            case SINGLE_NUMERICAL: {
                checkState(value.isNumber(),
                        "Expected a number but got %s:%s", value.getNodeType(), value);
                if (value.isNull()) {
                    return null;
                }
                return RawValue.singleNumerical(value.asDouble());
            }
            case CATEGORICALS_TO_NUMERICALS: {
                checkState(value.isObject(),
                        "Expected a map from integer to number, but got %s:%s", value.getNodeType(), value);
                if (value.isNull() || value.isEmpty()) {
                    return null;
                }
                ObjectNode node = (ObjectNode) value;
                Iterable<Map.Entry<String, JsonNode>> it = node::fields;
                int[] names = new int[value.size()];
                double[] values = new double[value.size()];

                int i = 0;
                for (Map.Entry<String, JsonNode> entry : it) {
                    Integer name = Ints.tryParse(entry.getKey());
                    checkState(name != null,
                            "Expected a map from integer to number, but got %s:%s", value.getNodeType(), value);
                    names[i] = name;
                    checkState(entry.getValue().isNumber(),
                            "Expected a map from integer to number, but got %s:%s", value.getNodeType(), value);
                    values[i] = entry.getValue().asDouble();
                    i++;
                }
                return RawValue.categoricalsToNumericals(names, values);
            }
            case STRINGS_TO_NUMERICALS: {
                checkState(value.isObject(),
                        "Expected a map from integer to number, but got %s:%s", value.getNodeType(), value);
                if (value.isNull() || value.isEmpty()) {
                    return null;
                }
                ObjectNode node = (ObjectNode) value;
                Iterable<Map.Entry<String, JsonNode>> it = node::fields;
                String[] names = new String[value.size()];
                double[] values = new double[value.size()];

                int i = 0;
                for (Map.Entry<String, JsonNode> entry : it) {
                    names[i] = entry.getKey();
                    checkState(entry.getValue().isNumber(),
                            "Expected a map from integer to number, but got %s:%s", value.getNodeType(), value);
                    values[i] = entry.getValue().asDouble();
                    i++;
                }
                return RawValue.stringsToNumericals(names, values);
            }
            default: throw new AssertionError(String.format("Unknown type:%s", key.getValueType()));
        }
    }
}
