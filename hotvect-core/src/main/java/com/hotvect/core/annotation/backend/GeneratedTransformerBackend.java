package com.hotvect.core.annotation.backend;

/**
 * Backend-specific strategy used by the {@code @GenerateSimpleRankingTransformer} annotation processor to
 * resolve the {@code ValueType} of a generated feature namespace.
 *
 * <p>Implementations live in backend modules (e.g. {@code hotvect-catboost}, {@code hotvect-tensorflow}) and
 * are selected with {@code @GenerateSimpleRankingTransformer(backend = ...)}. The processor loads the selected
 * backend reflectively from the annotation processor path and calls {@link #resolve} once per output feature, so
 * the processor itself stays backend-agnostic.</p>
 *
 * <p>Because the backend is loaded inside the compiler, implementations must be dependency-light: they should
 * not trigger loading of heavyweight or native runtime classes during annotation processing.</p>
 *
 * <p>Implementations must provide an accessible no-argument constructor. The {@code declaredType} passed to
 * {@link #resolve} is the optional {@code transformer_parameters.features[].type} value for a single output feature;
 * backend selection itself comes from {@code @GenerateSimpleRankingTransformer.backend}.</p>
 */
public interface GeneratedTransformerBackend {
    /**
     * Resolves the feature {@code ValueType} for one output feature.
     *
     * @param declaredType the backend-specific type string from {@code transformer_parameters.features[].type},
     *                     or {@code null}/blank when the type should be inferred from {@code returnTypeName}.
     * @param returnTypeName the erased canonical name of the feature method's return type, for example
     *                       {@code "double"}, {@code "float[]"} or {@code "java.lang.String"}.
     * @return a {@link Resolution} carrying either the Java source expression that constructs the feature
     *         {@code ValueType}, or a human-readable error describing why the feature cannot be resolved.
     */
    Resolution resolve(String declaredType, String returnTypeName);
}
