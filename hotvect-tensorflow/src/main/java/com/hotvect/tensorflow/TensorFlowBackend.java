package com.hotvect.tensorflow;

import com.hotvect.core.annotation.backend.GeneratedTransformerBackend;
import com.hotvect.core.annotation.backend.Resolution;

import java.util.Locale;
import java.util.Set;

/**
 * {@link GeneratedTransformerBackend} for TensorFlow. Owns the TensorFlow feature-type grammar (dtype plus optional
 * rank-1 shape encoded in the type string, e.g. {@code "float32"} or {@code "float32[768]"}), inference from
 * Java return types, and which return types each dtype accepts.
 *
 * <p>This class is loaded inside the compiler during annotation processing; it intentionally only produces a
 * source expression string and does not reference {@link TensorFlowFeatureType} (which transitively pulls the
 * TensorFlow runtime) during processing.</p>
 */
public final class TensorFlowBackend implements GeneratedTransformerBackend {
    private static final String FEATURE_TYPE_FQN = "com.hotvect.tensorflow.TensorFlowFeatureType";
    private static final Set<String> INT64_SCALAR_RETURNS = Set.of(
            "int", "long", "java.lang.Integer", "java.lang.Long");
    private static final Set<String> FLOAT32_SCALAR_RETURNS = Set.of(
            "float", "double", "java.lang.Float", "java.lang.Double");

    @Override
    public Resolution resolve(String declaredType, String returnTypeName) {
        Parsed parsed;
        if (declaredType != null && !declaredType.isBlank()) {
            parsed = parse(declaredType.trim());
            if (parsed == null) {
                return Resolution.error("Invalid TensorFlow feature type '" + declaredType
                        + "'. Use one of: int64, float32, string, int64[N], float32[N].");
            }
        } else {
            parsed = infer(returnTypeName);
            if (parsed == null) {
                return Resolution.error("Unsupported TensorFlow output type for return type '" + returnTypeName
                        + "'. Declare transformer_parameters.features[].type (e.g. float32, int64, string, float32[768]), "
                        + "or return int/Integer/long/Long/float/Float/double/Double/String.");
            }
        }

        if (!acceptsReturnType(parsed, returnTypeName)) {
            return Resolution.error("TensorFlow feature declares type '" + parsed.describe()
                    + "' but returns " + returnTypeName + ".");
        }
        return Resolution.of(expression(parsed));
    }

    private record Parsed(String dtype, int length) {
        // length 0 means a scalar; a positive length means a rank-1 sequence
        private String describe() {
            return length == 0 ? dtype : dtype + "[" + length + "]";
        }
    }

    private static Parsed parse(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        int bracket = lower.indexOf('[');
        if (bracket < 0) {
            return switch (lower) {
                case "int64" -> new Parsed("int64", 0);
                case "float32" -> new Parsed("float32", 0);
                case "string" -> new Parsed("string", 0);
                default -> null;
            };
        }
        if (!lower.endsWith("]")) {
            return null;
        }
        String dtype = lower.substring(0, bracket);
        // String vectors are not supported.
        if (!dtype.equals("int64") && !dtype.equals("float32")) {
            return null;
        }
        String inner = lower.substring(bracket + 1, lower.length() - 1).trim();
        int length;
        try {
            length = Integer.parseInt(inner);
        } catch (NumberFormatException e) {
            return null;
        }
        if (length <= 0) {
            return null;
        }
        return new Parsed(dtype, length);
    }

    private static Parsed infer(String returnTypeName) {
        if (INT64_SCALAR_RETURNS.contains(returnTypeName)) {
            return new Parsed("int64", 0);
        }
        if (FLOAT32_SCALAR_RETURNS.contains(returnTypeName)) {
            return new Parsed("float32", 0);
        }
        if (returnTypeName.equals("java.lang.String")) {
            return new Parsed("string", 0);
        }
        // Array features cannot be inferred without an explicit length.
        return null;
    }

    private static boolean acceptsReturnType(Parsed parsed, String returnTypeName) {
        if (parsed.length() == 0) {
            return switch (parsed.dtype()) {
                case "int64" -> INT64_SCALAR_RETURNS.contains(returnTypeName);
                case "float32" -> FLOAT32_SCALAR_RETURNS.contains(returnTypeName);
                case "string" -> returnTypeName.equals("java.lang.String");
                default -> false;
            };
        }
        return switch (parsed.dtype()) {
            case "int64" -> returnTypeName.equals("int[]") || returnTypeName.equals("long[]");
            case "float32" -> returnTypeName.equals("float[]") || returnTypeName.equals("double[]");
            default -> false;
        };
    }

    private static String expression(Parsed parsed) {
        if (parsed.length() == 0) {
            return switch (parsed.dtype()) {
                case "int64" -> FEATURE_TYPE_FQN + ".CATEGORICAL";
                case "float32" -> FEATURE_TYPE_FQN + ".NUMERICAL";
                case "string" -> FEATURE_TYPE_FQN + ".STRING";
                default -> throw new IllegalStateException("Unsupported dtype: " + parsed.dtype());
            };
        }
        return switch (parsed.dtype()) {
            case "int64" -> FEATURE_TYPE_FQN + ".categoricalSequence(" + parsed.length() + ")";
            case "float32" -> FEATURE_TYPE_FQN + ".numericalSequence(" + parsed.length() + ")";
            default -> throw new IllegalStateException("Unsupported sequence dtype: " + parsed.dtype());
        };
    }
}
