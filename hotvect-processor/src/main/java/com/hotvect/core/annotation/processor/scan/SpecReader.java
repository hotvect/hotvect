package com.hotvect.core.annotation.processor.scan;

import java.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.hotvect.core.annotation.processor.ProcessingContext;
import com.hotvect.core.annotation.processor.model.FeatureSpec;
import com.hotvect.core.annotation.processor.model.TransformerSpec;
import com.hotvect.core.annotation.processor.util.AnnotationUtils;

public final class SpecReader {
    private final ProcessingContext context;

    public SpecReader(ProcessingContext context) {
        this.context = context;
    }

    public TransformerSpec readSpec(TypeElement element) {
        Types types = context.types();
        AnnotationMirror mirror = AnnotationUtils.getAnnotationMirror(element, context.specAnnotation(), types);
        if (mirror == null) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Missing @GenerateSimpleRankingTransformer annotation data.", element);
            return null;
        }

        String name = AnnotationUtils.getAnnotationValue(mirror, "name", String.class, "GeneratedRankingTransformer");
        String packageName = AnnotationUtils.getAnnotationValue(mirror, "packageName", String.class, "");
        TypeMirror sharedType = AnnotationUtils.getAnnotationValue(mirror, "sharedType", TypeMirror.class, null);
        TypeMirror actionType = AnnotationUtils.getAnnotationValue(mirror, "actionType", TypeMirror.class, null);
        List<TypeMirror> featureClasses = AnnotationUtils.getAnnotationValue(mirror, "features", List.class, List.of());
        TypeMirror backend = AnnotationUtils.getAnnotationValue(mirror, "backend", TypeMirror.class, null);
        String algorithmDefinitionResource = AnnotationUtils.getAnnotationValue(mirror, "algorithmDefinitionResource", String.class, "");

        AlgorithmDefinitionReader definitionReader = new AlgorithmDefinitionReader(context);
        List<FeatureSpec> outputFeatures = definitionReader.readOutputFeatures(element, algorithmDefinitionResource);
        if (outputFeatures == null) {
            return null;
        }

        if (sharedType == null || actionType == null || backend == null) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "sharedType, actionType, and backend are required on @GenerateSimpleRankingTransformer.", element);
            return null;
        }

        if (featureClasses.isEmpty()) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "features list is empty on @GenerateSimpleRankingTransformer.", element);
            return null;
        }

        return new TransformerSpec(name, packageName, sharedType, actionType, featureClasses,
                algorithmDefinitionResource, backend, outputFeatures);
    }
}
