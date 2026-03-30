package com.hotvect.tensorflow;

import com.google.protobuf.ByteString;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import org.tensorflow.proto.BytesList;
import org.tensorflow.proto.Example;
import org.tensorflow.proto.Feature;
import org.tensorflow.proto.Features;
import org.tensorflow.proto.FloatList;
import org.tensorflow.proto.Int64List;

import java.util.List;

/**
 * Encodes HotVect transformer outputs into {@link Example} protobufs following the {@link TensorFlowFeatureType}
 * dtype + shape contract.
 */
public final class TensorFlowExampleEncoder {

    private static final float DEFAULT_FLOAT = 0.0f;
    private static final long DEFAULT_INT64 = 0L;
    private static final String DEFAULT_STRING = "";

    private TensorFlowExampleEncoder() {
    }

    public static Example encodeTransformerFeatures(Iterable<? extends Namespace> usedFeatures,
                                                    NamespacedRecord<Namespace, Object> record) {
        Features.Builder builder = Features.newBuilder();
        putTransformerFeatures(builder, usedFeatures, record);
        return Example.newBuilder().setFeatures(builder.build()).build();
    }

    public static byte[] encodeTransformerFeaturesBytes(Iterable<? extends Namespace> usedFeatures,
                                                        NamespacedRecord<Namespace, Object> record) {
        return encodeTransformerFeatures(usedFeatures, record).toByteArray();
    }

    public static void putTransformerFeatures(Features.Builder featuresBuilder,
                                              Iterable<? extends Namespace> usedFeatures,
                                              NamespacedRecord<Namespace, Object> record) {
        for (Namespace featureKey : usedFeatures) {
            putTransformerFeature(featuresBuilder, featureKey, record.get(featureKey));
        }
    }

    public static void putTransformerFeature(Features.Builder featuresBuilder, Namespace featureKey, Object value) {
        Object featureValueTypeObj = featureKey.getFeatureValueType();
        if (!(featureValueTypeObj instanceof TensorFlowFeatureType featureType)) {
            String actual = featureValueTypeObj == null ? "null" : featureValueTypeObj.getClass().getName();
            throw new IllegalArgumentException(
                    "Feature '" + featureKey.getName() + "' must have TensorFlowFeatureType FeatureValueType (was " + actual + ")"
            );
        }

        Feature encoded = encode(featureKey.getName(), featureType, value);
        featuresBuilder.putFeature(featureKey.getName(), encoded);
    }

    private static Feature encode(String featureName, TensorFlowFeatureType featureType, Object value) {
        return switch (featureType) {
            case TensorFlowFeatureType.Numerical ignored -> encodeFloatScalar(featureName, value);
            case TensorFlowFeatureType.Categorical ignored -> encodeInt64Scalar(featureName, value);
            case TensorFlowFeatureType.StringType ignored -> encodeStringScalar(value);
            case TensorFlowFeatureType.NumericalSequence seq -> encodeFloatVector(featureName, value, seq.length());
            case TensorFlowFeatureType.CategoricalSequence seq -> encodeInt64Vector(featureName, value, seq.length());
        };
    }

    private static Feature encodeFloatScalar(String featureName, Object value) {
        float floatValue;
        if (value == null) {
            floatValue = DEFAULT_FLOAT;
        } else if (value instanceof Number n) {
            floatValue = n.floatValue();
        } else {
            throw new IllegalArgumentException("Feature '" + featureName + "' expected Number for float32 scalar, got: " + value.getClass().getName());
        }
        return Feature.newBuilder()
                .setFloatList(FloatList.newBuilder().addValue(floatValue).build())
                .build();
    }

    private static Feature encodeInt64Scalar(String featureName, Object value) {
        long longValue;
        if (value == null) {
            longValue = DEFAULT_INT64;
        } else if (value instanceof Number n) {
            longValue = n.longValue();
        } else {
            throw new IllegalArgumentException("Feature '" + featureName + "' expected Number for int64 scalar, got: " + value.getClass().getName());
        }
        return Feature.newBuilder()
                .setInt64List(Int64List.newBuilder().addValue(longValue).build())
                .build();
    }

    private static Feature encodeStringScalar(Object value) {
        String s;
        if (value == null) {
            s = DEFAULT_STRING;
        } else if (value instanceof String str) {
            s = str;
        } else {
            s = value.toString();
        }
        return Feature.newBuilder()
                .setBytesList(BytesList.newBuilder().addValue(ByteString.copyFromUtf8(s)).build())
                .build();
    }

    private static Feature encodeInt64Vector(String featureName, Object value, int length) {
        Int64List.Builder builder = Int64List.newBuilder();
        switch (value) {
            case null -> addInt64Defaults(builder, length);
            case int[] arr -> {
                validateVectorLength(featureName, "int64", length, arr.length);
                for (int element : arr) {
                    builder.addValue(element);
                }
            }
            case long[] arr -> {
                validateVectorLength(featureName, "int64", length, arr.length);
                for (long element : arr) {
                    builder.addValue(element);
                }
            }
            case Integer[] arr -> {
                validateVectorLength(featureName, "int64", length, arr.length);
                for (Integer element : arr) {
                    builder.addValue(element != null ? element.longValue() : DEFAULT_INT64);
                }
            }
            case Long[] arr -> {
                validateVectorLength(featureName, "int64", length, arr.length);
                for (Long element : arr) {
                    builder.addValue(element != null ? element : DEFAULT_INT64);
                }
            }
            case List<?> list -> appendInt64List(featureName, builder, list, length);
            default -> throw new IllegalArgumentException(
                    "Feature '" + featureName + "' expected int64 vector value, got: " + value.getClass().getName()
            );
        }
        return Feature.newBuilder().setInt64List(builder.build()).build();
    }

    private static Feature encodeFloatVector(String featureName, Object value, int length) {
        FloatList.Builder builder = FloatList.newBuilder();
        switch (value) {
            case null -> addFloatDefaults(builder, length);
            case float[] arr -> {
                validateVectorLength(featureName, "float32", length, arr.length);
                for (float element : arr) {
                    builder.addValue(element);
                }
            }
            case double[] arr -> {
                validateVectorLength(featureName, "float32", length, arr.length);
                for (double element : arr) {
                    builder.addValue((float) element);
                }
            }
            case Float[] arr -> {
                validateVectorLength(featureName, "float32", length, arr.length);
                for (Float element : arr) {
                    builder.addValue(element != null ? element : DEFAULT_FLOAT);
                }
            }
            case Double[] arr -> {
                validateVectorLength(featureName, "float32", length, arr.length);
                for (Double element : arr) {
                    builder.addValue(element != null ? element.floatValue() : DEFAULT_FLOAT);
                }
            }
            case List<?> list -> appendFloatList(featureName, builder, list, length);
            default -> throw new IllegalArgumentException(
                    "Feature '" + featureName + "' expected float32 vector value, got: " + value.getClass().getName()
            );
        }
        return Feature.newBuilder().setFloatList(builder.build()).build();
    }

    private static void validateVectorLength(String featureName, String expectedType, int expectedLength, int actualLength) {
        if (actualLength != expectedLength) {
            throw new IllegalArgumentException(
                    "Feature '" + featureName + "' expected " + expectedType + " vector length " + expectedLength + ", got " + actualLength
            );
        }
    }

    private static void addInt64Defaults(Int64List.Builder builder, int length) {
        for (int i = 0; i < length; i++) {
            builder.addValue(DEFAULT_INT64);
        }
    }

    private static void addFloatDefaults(FloatList.Builder builder, int length) {
        for (int i = 0; i < length; i++) {
            builder.addValue(DEFAULT_FLOAT);
        }
    }

    private static void appendInt64List(String featureName, Int64List.Builder builder, List<?> list, int length) {
        validateVectorLength(featureName, "int64", length, list.size());
        for (Object element : list) {
            if (element == null) {
                builder.addValue(DEFAULT_INT64);
            } else if (element instanceof Number number) {
                builder.addValue(number.longValue());
            } else {
                throw new IllegalArgumentException(
                        "Feature '" + featureName + "' expected numeric elements for int64 vector, got: " + element.getClass().getName()
                );
            }
        }
    }

    private static void appendFloatList(String featureName, FloatList.Builder builder, List<?> list, int length) {
        validateVectorLength(featureName, "float32", length, list.size());
        for (Object element : list) {
            if (element == null) {
                builder.addValue(DEFAULT_FLOAT);
            } else if (element instanceof Number number) {
                builder.addValue(number.floatValue());
            } else {
                throw new IllegalArgumentException(
                        "Feature '" + featureName + "' expected numeric elements for float32 vector, got: " + element.getClass().getName()
                );
            }
        }
    }
}
