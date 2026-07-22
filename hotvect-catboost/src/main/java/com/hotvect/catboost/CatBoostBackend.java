package com.hotvect.catboost;

import com.hotvect.core.annotation.backend.GeneratedTransformerBackend;
import com.hotvect.core.annotation.backend.Resolution;

import java.util.Locale;
import java.util.Set;

/**
 * {@link GeneratedTransformerBackend} for CatBoost. Owns the CatBoost feature-type matrix (declared type and inference
 * rules, plus which Java return types each type accepts) so that the annotation processor does not need to.
 *
 * <p>This class is loaded inside the compiler during annotation processing; it intentionally only produces a
 * source expression string and does not reference {@link CatBoostFeatureType} at runtime.</p>
 */
public final class CatBoostBackend implements GeneratedTransformerBackend {
    private static final String FEATURE_TYPE_FQN = "com.hotvect.catboost.CatBoostFeatureType";
    private static final Set<String> VALID_TYPES = Set.of("CATEGORICAL", "NUMERICAL", "GROUP_ID", "TEXT", "EMBEDDING");
    private static final Set<String> CATEGORICAL_RETURNS = Set.of(
            "java.lang.String", "boolean", "int", "long",
            "java.lang.Boolean", "java.lang.Integer", "java.lang.Long");
    private static final Set<String> NUMERICAL_RETURNS = Set.of(
            "float", "double", "java.lang.Float", "java.lang.Double");

    @Override
    public Resolution resolve(String declaredType, String returnTypeName) {
        String type;
        if (declaredType != null && !declaredType.isBlank()) {
            type = declaredType.trim().toUpperCase(Locale.ROOT);
            if (!VALID_TYPES.contains(type)) {
                return Resolution.error("Invalid CatBoost feature type '" + declaredType
                        + "'. Valid values: " + VALID_TYPES + ".");
            }
        } else {
            type = infer(returnTypeName);
            if (type == null) {
                return Resolution.error("Unsupported CatBoost output type for return type '" + returnTypeName
                        + "'. Allowed: CATEGORICAL (String/int/Integer/long/Long/boolean/Boolean), "
                        + "NUMERICAL (float/Float/double/Double), TEXT (String[]), EMBEDDING (float[]/double[]). "
                        + "Or set transformer_parameters.features[].type explicitly.");
            }
        }

        if (!acceptsReturnType(type, returnTypeName)) {
            return Resolution.error("CatBoost feature declares type '" + type
                    + "' but returns " + returnTypeName + ".");
        }
        return Resolution.of(FEATURE_TYPE_FQN + "." + type);
    }

    private static String infer(String returnTypeName) {
        if (CATEGORICAL_RETURNS.contains(returnTypeName)) {
            return "CATEGORICAL";
        }
        if (NUMERICAL_RETURNS.contains(returnTypeName)) {
            return "NUMERICAL";
        }
        if (returnTypeName.equals("java.lang.String[]")) {
            return "TEXT";
        }
        if (returnTypeName.equals("float[]") || returnTypeName.equals("double[]")) {
            return "EMBEDDING";
        }
        return null;
    }

    private static boolean acceptsReturnType(String type, String returnTypeName) {
        return switch (type) {
            case "CATEGORICAL" -> CATEGORICAL_RETURNS.contains(returnTypeName);
            case "NUMERICAL" -> NUMERICAL_RETURNS.contains(returnTypeName);
            case "GROUP_ID" -> returnTypeName.equals("java.lang.String");
            case "TEXT" -> returnTypeName.equals("java.lang.String[]");
            case "EMBEDDING" -> returnTypeName.equals("float[]") || returnTypeName.equals("double[]");
            default -> false;
        };
    }
}
