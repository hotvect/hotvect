package com.hotvect.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface InjectAlgorithm {
    /**
     * The unversioned dependency name. Stage 2 dependencies select exactly one algorithm, which
     * the composite factory supplies through {@code AlgorithmDependencies.only(...)}. For
     * a parameterized algorithm type, the generated transformer exposes a public {@code TypeToken}
     * constant so that lookup preserves the complete generic contract.
     */
    String value();
}
