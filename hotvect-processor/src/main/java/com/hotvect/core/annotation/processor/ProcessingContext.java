package com.hotvect.core.annotation.processor;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public record ProcessingContext(Messager messager, Filer filer, Elements elements, Types types,
                                TypeElement sharedFeatureAnnotation, TypeElement featureAnnotation,
                                TypeElement injectAnnotation, TypeElement specAnnotation,
                                TypeElement sharedContextType) {
}
