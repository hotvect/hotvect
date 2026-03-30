package com.hotvect.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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
     * Optional classpath resource path to an algorithm definition JSON file.
     * When provided, the annotation processor will read transformer_parameters.features
     * to determine output features at compile time.
     */
    String algorithmDefinitionResource() default "";
}
