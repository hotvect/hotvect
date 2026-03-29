package com.hotvect.core.annotation.processor.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.hotvect.core.annotation.processor.ProcessingContext;
import com.hotvect.core.annotation.processor.model.FeatureKind;
import com.hotvect.core.annotation.processor.model.FeatureNode;
import com.hotvect.core.annotation.processor.model.FeatureScanResult;
import com.hotvect.core.annotation.processor.model.Param;
import com.hotvect.core.annotation.processor.model.ParamKind;
import com.hotvect.core.annotation.processor.model.TransformerSpec;
import com.hotvect.core.annotation.processor.util.AnnotationUtils;
import com.hotvect.core.annotation.processor.util.TypeUtils;

public final class FeatureScanner {
    private final ProcessingContext context;

    public FeatureScanner(ProcessingContext context) {
        this.context = context;
    }

    public FeatureScanResult scan(TransformerSpec spec) {
        Map<String, FeatureNode> featureNodes = new LinkedHashMap<>();
        Map<TypeElement, List<FeatureNode>> featuresByClass = new LinkedHashMap<>();

        Types types = context.types();
        for (TypeMirror featureClass : spec.featureClasses()) {
            TypeElement featureType = (TypeElement) types.asElement(featureClass);
            if (featureType == null) {
                error(context, null, "Unable to resolve feature class: %s", featureClass);
                continue;
            }
            List<FeatureNode> discovered = discoverFeatures(featureType, spec, featureNodes);
            featuresByClass.put(featureType, discovered);
        }

        return new FeatureScanResult(featureNodes, featuresByClass);
    }

    private List<FeatureNode> discoverFeatures(TypeElement featureType, TransformerSpec spec, Map<String, FeatureNode> allNodes) {
        List<FeatureNode> discovered = new ArrayList<>();
        for (Element enclosed : featureType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;

            ExecutableElement method = (ExecutableElement) enclosed;
            FeatureKind kind = getFeatureKind(method);
            if (kind == null) continue;

            if (!method.getModifiers().contains(Modifier.STATIC)) {
                error(context, method, "Feature methods must be static.");
                continue;
            }

            String name = getFeatureName(method, kind);
            if (name == null || name.isBlank()) {
                error(context, method, "Feature name must be non-empty.");
                continue;
            }
            if (!isIdentifierLike(name) || SourceVersion.isKeyword(name)) {
                error(context, method,
                        "Feature name '%s' must match ^[A-Za-z][A-Za-z0-9_]*$ and not be a Java keyword.",
                        name);
                continue;
            }

            if (allNodes.containsKey(name)) {
                error(context, method, "Duplicate feature name '%s'.", name);
                continue;
            }
            if (containsCaseInsensitive(allNodes, name)) {
                error(context, method,
                        "Feature name '%s' conflicts with an existing feature name (case-insensitive uniqueness required).",
                        name);
                continue;
            }

            List<Param> params = parseParameters(method, kind, spec);
            if (params == null) {
                continue;
            }

            FeatureNode node = new FeatureNode(name, kind, method, method.getReturnType(), params);
            allNodes.put(name, node);
            discovered.add(node);
        }
        return discovered;
    }

    private List<Param> parseParameters(ExecutableElement method, FeatureKind kind, TransformerSpec spec) {
        List<Param> params = new ArrayList<>();
        boolean hasShared = false;
        boolean hasAction = false;

        for (VariableElement param : method.getParameters()) {
            ParamKind paramKind = classifyParameter(param, kind, spec);
            if (paramKind == null) {
                error(context, param, "Unsupported parameter type in feature method: %s", param.asType());
                return null;
            }

            String injectName = null;
            if (paramKind == ParamKind.INJECTED) {
                injectName = getInjectName(param);
                if (injectName == null || injectName.isBlank()) {
                    error(context, param, "@Inject value must be non-empty.");
                    return null;
                }
            }

            if (paramKind == ParamKind.SHARED) {
                hasShared = true;
            } else if (paramKind == ParamKind.ACTION) {
                hasAction = true;
            }

            params.add(new Param(param, paramKind, injectName));
        }

        if (kind == FeatureKind.ACTION && !hasAction) {
            warn(context, method, "Action feature '%s' does not declare an ACTION parameter; it may be shared.", getFeatureName(method, kind));
        }

        if (kind == FeatureKind.SHARED && hasAction) {
            error(context, method, "Shared feature must not declare an ACTION parameter.");
            return null;
        }

        return params;
    }

    private ParamKind classifyParameter(VariableElement param, FeatureKind kind, TransformerSpec spec) {
        if (AnnotationUtils.getAnnotationMirror(param, context.injectAnnotation(), context.types()) != null) {
            return ParamKind.INJECTED;
        }

        TypeMirror paramType = param.asType();

        if (TypeUtils.isSharedContext(paramType, context.sharedContextType(), context.types())) {
            return ParamKind.CONTEXT;
        }

        if (kind == FeatureKind.ACTION && context.types().isAssignable(paramType, spec.actionType())) {
            return ParamKind.ACTION;
        }

        if (context.types().isAssignable(paramType, spec.sharedType())) {
            return ParamKind.SHARED;
        }

        return null;
    }

    private FeatureKind getFeatureKind(ExecutableElement method) {
        if (AnnotationUtils.getAnnotationMirror(method, context.sharedFeatureAnnotation(), context.types()) != null) {
            return FeatureKind.SHARED;
        }
        if (AnnotationUtils.getAnnotationMirror(method, context.featureAnnotation(), context.types()) != null) {
            return FeatureKind.ACTION;
        }
        return null;
    }

    private String getFeatureName(ExecutableElement method, FeatureKind kind) {
        if (kind == FeatureKind.SHARED) {
            return AnnotationUtils.getAnnotationValue(
                    AnnotationUtils.getAnnotationMirror(method, context.sharedFeatureAnnotation(), context.types()),
                    "value",
                    String.class,
                    null
            );
        }
        return AnnotationUtils.getAnnotationValue(
                AnnotationUtils.getAnnotationMirror(method, context.featureAnnotation(), context.types()),
                "value",
                String.class,
                null
        );
    }

    private String getInjectName(VariableElement param) {
        return AnnotationUtils.getAnnotationValue(
                AnnotationUtils.getAnnotationMirror(param, context.injectAnnotation(), context.types()),
                "value",
                String.class,
                null
        );
    }

    private static void error(ProcessingContext context, Element element, String message, Object... args) {
        context.messager().printMessage(Diagnostic.Kind.ERROR, String.format(Locale.ROOT, message, args), element);
    }

    private static void warn(ProcessingContext context, Element element, String message, Object... args) {
        context.messager().printMessage(Diagnostic.Kind.WARNING, String.format(Locale.ROOT, message, args), element);
    }

    private boolean isIdentifierLike(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!Character.isLetter(name.charAt(0))) return false;

        return name.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_');
    }

    private boolean containsCaseInsensitive(Map<String, FeatureNode> allNodes, String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        for (String existing : allNodes.keySet()) {
            if (existing.toLowerCase(Locale.ROOT).equals(lowered)) {
                return true;
            }
        }
        return false;
    }
}
