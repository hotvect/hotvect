package com.hotvect.core.annotation.processor.model;

import java.util.List;

import javax.lang.model.type.TypeMirror;

public record TransformerSpec(String name, String packageName, TypeMirror sharedType,
                              TypeMirror actionType, List<TypeMirror> featureClasses,
                              String algorithmDefinitionResource, TypeMirror backend,
                              List<FeatureSpec> outputFeatures) {}
