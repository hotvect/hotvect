package com.hotvect.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.hotvect.core.annotation.backend.GeneratedTransformerBackend;

@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface GenerateSimpleRankingTransformer {
    String name() default "GeneratedRankingTransformer";

    String packageName() default "";

    Class<?> sharedType();

    Class<?> actionType();

    Class<?>[] features();

    /**
     * The backend {@link GeneratedTransformerBackend} that resolves generated feature namespaces to a backend-specific
     * {@code ValueType}. The backend class must be on both the compile classpath (for this reference) and the
     * annotation processor path (so the processor can load it). For example
     * {@code com.hotvect.catboost.CatBoostBackend} or
     * {@code com.hotvect.tensorflow.TensorFlowBackend}.
     *
     * <p>Backend selection is compile-time Java configuration. Do not put generated transformer backend selection in
     * the algorithm definition; {@code transformer_parameters.features[].type} only pins individual feature types for
     * the backend selected here.</p>
     */
    Class<? extends GeneratedTransformerBackend> backend();

    /**
     * Classpath resource path to an algorithm definition JSON file. The annotation processor reads
     * {@code transformer_parameters.features} to determine the generated output features at compile time.
     */
    String algorithmDefinitionResource() default "";
}
