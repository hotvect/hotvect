package com.hotvect.core.annotation.processor.codegen;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.hotvect.core.annotation.processor.ProcessingContext;
import com.hotvect.core.annotation.processor.model.Analysis;
import com.hotvect.core.annotation.processor.model.FeatureKind;
import com.hotvect.core.annotation.processor.model.FeatureNode;
import com.hotvect.core.annotation.processor.model.FeatureScanResult;
import com.hotvect.core.annotation.processor.model.Param;
import com.hotvect.core.annotation.processor.model.ParamKind;
import com.hotvect.core.annotation.processor.model.TransformerSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

public final class SimpleRankingTransformerGenerator {
    private static final String DEFAULT_GENERATED_NAME = "GeneratedRankingTransformer";

    private final ProcessingContext context;

    public SimpleRankingTransformerGenerator(ProcessingContext context) {
        this.context = context;
    }

    public void generate(TypeElement specElement, TransformerSpec spec, FeatureScanResult scanResult, Analysis analysis) {
        if (hasErrors(specElement, analysis)) return;

        String packageName = resolvePackage(specElement, spec);
        String className = resolveClassName(specElement, spec);
        String qualifiedName = packageName.isBlank() ? className : packageName + "." + className;
        if (context.elements().getTypeElement(qualifiedName) != null) {
            context.messager().printMessage(Diagnostic.Kind.WARNING,
                    "Skipping generation for " + qualifiedName + " because the type already exists.",
                    specElement);
            return;
        }

        if (spec.outputFeatures() == null || spec.outputFeatures().isEmpty()) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Output features must be provided via algorithm definition; runtime output selection is not supported.",
                    specElement);
            return;
        }

        Map<String, FeatureNode> nodesByName = scanResult.nodesByName();
        Set<FeatureNode> reachable = analysis.reachable();
        List<FeatureNode> sharedNodes = new ArrayList<>();
        List<FeatureNode> actionNodes = new ArrayList<>();
        for (FeatureNode node : nodesByName.values()) {
            if (!reachable.contains(node)) {
                continue;
            }
            if (node.kind() == FeatureKind.SHARED) {
                sharedNodes.add(node);
            } else {
                actionNodes.add(node);
            }
        }

        Map<FeatureNode, List<FeatureNode>> sharedDeps = buildDependencies(sharedNodes, nodesByName, FeatureKind.SHARED);
        Map<FeatureNode, List<FeatureNode>> actionDeps = buildDependencies(actionNodes, nodesByName, FeatureKind.ACTION);

        List<FeatureNode> sharedOrder = topoSort(sharedNodes, sharedDeps);
        List<FeatureNode> actionOrder = topoSort(actionNodes, actionDeps);

        Map<String, String> sharedFields = buildFieldNames(sharedOrder);
        Map<String, String> actionFields = buildFieldNames(actionOrder);

        TypeName sharedType = TypeName.get(spec.sharedType());
        TypeName actionType = TypeName.get(spec.actionType());

        List<FeatureNode> outputNodes = new ArrayList<>();
        for (String name : spec.outputFeatures()) {
            FeatureNode node = nodesByName.get(name);
            if (node != null) {
                outputNodes.add(node);
            }
        }
        boolean outputTypeErrors = false;
        for (FeatureNode node : outputNodes) {
            String catBoostType = inferCatBoostType(node.returnType());
            if (catBoostType == null) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "Unsupported output type for feature '" + node.name() + "' (" + node.returnType()
                                + "). Allowed output types: CATEGORICAL: String, int/Integer, long/Long, boolean/Boolean; "
                                + "NUMERICAL: float/Float, double/Double; TEXT: String[]; EMBEDDING: float[] or double[]. "
                                + "Convert inside the feature method if needed.",
                        node.method());
                outputTypeErrors = true;
            }
        }
        if (outputTypeErrors) {
            return;
        }
        Map<String, String> outputConstants = buildConstantNames(outputNodes);

        ClassName streamingTransformer = ClassName.get("com.hotvect.core.transform.ranking", "StreamingRankingTransformer");
        ClassName sharedContext = ClassName.get("com.hotvect.core.transform.ranking", "SharedContext");
        ClassName namespace = ClassName.get("com.hotvect.api.data", "Namespace");
        ClassName namespacedRecord = ClassName.get("com.hotvect.api.data.common", "NamespacedRecordImpl");
        ClassName featureStoreResponse = ClassName.get("com.hotvect.api.data.featurestore", "FeatureStoreResponse");
        ClassName rankingRequest = ClassName.get("com.hotvect.api.data.ranking", "RankingRequest");
        ClassName transformedAction = ClassName.get("com.hotvect.api.data.ranking", "TransformedAction");
        ClassName catBoostType = ClassName.get("com.hotvect.catboost", "CatBoostFeatureType");
        ClassName namespaces = ClassName.get("com.hotvect.core.transform", "Namespaces");
        ClassName listBatchingSpliterator = ClassName.get("com.hotvect.core.transform.ranking", "ListBatchingSpliterator");
        ClassName featureStoreRetriever = ClassName.get("com.hotvect.core.featurestore", "FeatureStoreRetriever");

        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(streamingTransformer, sharedType, actionType));

        for (FeatureNode node : outputNodes) {
            String constant = outputConstants.get(node.name());
            String catBoost = inferCatBoostType(node.returnType());
            FieldSpec field = FieldSpec.builder(namespace, constant, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.declareFeatureNamespace($T.$L, $S)", namespaces, catBoostType, catBoost, node.name())
                    .build();
            typeBuilder.addField(field);
        }

        typeBuilder.addType(buildHolder("SharedValues", sharedOrder, sharedFields));
        typeBuilder.addType(buildHolder("ActionValues", actionOrder, actionFields));
        FieldSpec usedFeaturesField = FieldSpec.builder(
                        ParameterizedTypeName.get(ClassName.get(SortedSet.class), namespace),
                        "USED_FEATURES",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T<>($T.alphabetical())", ClassName.get(TreeSet.class), namespace)
                .build();
        typeBuilder.addField(usedFeaturesField);
        CodeBlock.Builder usedInit = CodeBlock.builder();
        for (FeatureNode node : outputNodes) {
            String constant = outputConstants.get(node.name());
            usedInit.addStatement("USED_FEATURES.add($L)", constant);
        }
        typeBuilder.addStaticBlock(usedInit.build());

        TypeName featureStoreRetrieverType = ParameterizedTypeName.get(featureStoreRetriever, sharedType, actionType);
        typeBuilder.addField(FieldSpec.builder(featureStoreRetrieverType, "featureStoreRetriever", Modifier.PRIVATE, Modifier.FINAL).build());

        typeBuilder.addMethod(buildPrimaryConstructor(featureStoreRetrieverType));

        typeBuilder.addMethod(buildTransformStream(sharedType, actionType, sharedContext, rankingRequest,
                featureStoreResponse));
        typeBuilder.addMethod(buildTransformBatch(sharedType, actionType, sharedContext, rankingRequest,
                featureStoreResponse, listBatchingSpliterator));
        typeBuilder.addMethod(buildGetUsedFeatures(namespace));

        typeBuilder.addMethod(buildComputeShared(sharedOrder, sharedFields, nodesByName, sharedType, sharedContext));
        typeBuilder.addMethod(buildComputeAction(actionOrder, sharedFields, actionFields, nodesByName,
                sharedType, actionType, sharedContext));
        typeBuilder.addMethod(buildTransformAction(actionType, sharedType, sharedContext, transformedAction,
                namespacedRecord, namespace, outputNodes, outputConstants, actionFields));

        JavaFile javaFile = JavaFile.builder(packageName, typeBuilder.build())
                .indent("    ")
                .build();

        writeSourceFile(specElement, packageName, className, javaFile);
    }

    private boolean hasErrors(TypeElement specElement, Analysis analysis) {
        boolean hasErrors = false;
        Set<FeatureNode> reachable = analysis.reachable();
        for (Analysis.MissingDependency missing : analysis.missingDependencies()) {
            if (!reachable.contains(missing.feature())) {
                continue;
            }
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Missing dependency: " + missing.feature().name() + " injects '" + missing.missingName() + "'.",
                    missing.param());
            hasErrors = true;
        }
        for (Analysis.TypeMismatch mismatch : analysis.typeMismatches()) {
            if (!reachable.contains(mismatch.feature())) {
                continue;
            }
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Type mismatch: " + mismatch.feature().name() + " expects " + mismatch.param().asType()
                            + " but provider " + mismatch.provider().name() + " returns " + mismatch.provider().returnType() + ".",
                    mismatch.param());
            hasErrors = true;
        }
        for (Analysis.DirectionViolation violation : analysis.directionViolations()) {
            if (!reachable.contains(violation.feature())) {
                continue;
            }
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Shared feature " + violation.feature().name()
                            + " depends on action feature " + violation.provider().name() + ".",
                    violation.feature().method());
            hasErrors = true;
        }
        for (Analysis.OutputIssue issue : analysis.outputIssues()) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Invalid output feature '" + issue.featureName() + "': " + issue.message(),
                    specElement);
            hasErrors = true;
        }
        if (!analysis.cycles().isEmpty()) {
            for (List<FeatureNode> cycle : analysis.cycles()) {
                boolean intersects = false;
                for (FeatureNode node : cycle) {
                    if (reachable.contains(node)) {
                        intersects = true;
                        break;
                    }
                }
                if (intersects) {
                    context.messager().printMessage(Diagnostic.Kind.ERROR,
                            "Cycle detected in feature graph: " + cycle,
                            specElement);
                    hasErrors = true;
                }
            }
        }
        return hasErrors;
    }

    private String resolvePackage(TypeElement specElement, TransformerSpec spec) {
        if (spec.packageName() != null && !spec.packageName().isBlank()) {
            return spec.packageName();
        }
        return context.elements().getPackageOf(specElement).getQualifiedName().toString();
    }

    private String resolveClassName(TypeElement specElement, TransformerSpec spec) {
        String name = spec.name();
        if (name == null || name.isBlank() || DEFAULT_GENERATED_NAME.equals(name)) {
            String base = specElement.getSimpleName().toString();
            if (base.endsWith("Factory") && base.length() > "Factory".length()) {
                return base.substring(0, base.length() - "Factory".length());
            }
            return base;
        }
        return name;
    }

    private Map<FeatureNode, List<FeatureNode>> buildDependencies(List<FeatureNode> nodes,
                                                                  Map<String, FeatureNode> nodesByName,
                                                                  FeatureKind providerKind) {
        Map<FeatureNode, List<FeatureNode>> deps = new LinkedHashMap<>();
        for (FeatureNode node : nodes) {
            List<FeatureNode> nodeDeps = new ArrayList<>();
            for (Param param : node.params()) {
                if (param.kind() != ParamKind.INJECTED) {
                    continue;
                }
                FeatureNode provider = nodesByName.get(param.injectName());
                if (provider == null || provider.kind() != providerKind) {
                    continue;
                }
                nodeDeps.add(provider);
            }
            nodeDeps.sort(Comparator.comparing(FeatureNode::name));
            deps.put(node, nodeDeps);
        }
        return deps;
    }

    private List<FeatureNode> topoSort(List<FeatureNode> nodes, Map<FeatureNode, List<FeatureNode>> deps) {
        List<FeatureNode> ordered = new ArrayList<>();
        Set<FeatureNode> visited = new HashSet<>();
        Set<FeatureNode> visiting = new HashSet<>();

        List<FeatureNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing(FeatureNode::name));
        for (FeatureNode node : sorted) {
            dfs(node, deps, visited, visiting, ordered);
        }
        return ordered;
    }

    private void dfs(FeatureNode node,
                     Map<FeatureNode, List<FeatureNode>> deps,
                     Set<FeatureNode> visited,
                     Set<FeatureNode> visiting,
                     List<FeatureNode> ordered) {
        if (visited.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            return;
        }
        for (FeatureNode dep : deps.getOrDefault(node, List.of())) {
            dfs(dep, deps, visited, visiting, ordered);
        }
        visiting.remove(node);
        visited.add(node);
        ordered.add(node);
    }

    private Map<String, String> buildConstantNames(List<FeatureNode> nodes) {
        Map<String, String> constants = new LinkedHashMap<>();
        for (FeatureNode node : nodes) {
            constants.put(node.name(), toConstantName(node.name()));
        }
        return constants;
    }

    private String toConstantName(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    private String inferCatBoostType(TypeMirror returnType) {
        Types types = context.types();
        TypeElement stringType = context.elements().getTypeElement("java.lang.String");
        if (returnType.getKind() == TypeKind.ARRAY) {
            ArrayType arrayType = (ArrayType) returnType;
            TypeMirror component = arrayType.getComponentType();
            if (stringType != null && types.isSameType(types.erasure(component), types.erasure(stringType.asType()))) {
                return "TEXT";
            }
            if (component.getKind().isPrimitive()) {
                if (component.getKind() == TypeKind.FLOAT || component.getKind() == TypeKind.DOUBLE) {
                    return "EMBEDDING";
                }
                return null;
            }
            return null;
        }
        if (stringType != null && types.isSameType(types.erasure(returnType), types.erasure(stringType.asType()))) {
            return "CATEGORICAL";
        }
        if (returnType.getKind().isPrimitive()) {
            return switch (returnType.getKind()) {
                case BOOLEAN, INT, LONG -> "CATEGORICAL";
                case FLOAT, DOUBLE -> "NUMERICAL";
                default -> null;
            };
        }
        if (returnType.getKind() == TypeKind.DECLARED) {
            String raw = ((DeclaredType) returnType).asElement().toString();
            if ("java.lang.Boolean".equals(raw)
                    || "java.lang.Integer".equals(raw)
                    || "java.lang.Long".equals(raw)) {
                return "CATEGORICAL";
            }
            if ("java.lang.Float".equals(raw) || "java.lang.Double".equals(raw)) {
                return "NUMERICAL";
            }
        }
        return null;
    }

    private MethodSpec buildPrimaryConstructor(TypeName featureStoreRetrieverType) {
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(featureStoreRetrieverType, "featureStoreRetriever");
        ctor.addStatement("this.featureStoreRetriever = featureStoreRetriever");
        return ctor.build();
    }

    private MethodSpec buildTransformStream(TypeName sharedType,
                                            TypeName actionType,
                                            ClassName sharedContext,
                                            ClassName rankingRequest,
                                            ClassName featureStoreResponse) {
        ParameterizedTypeName requestType = ParameterizedTypeName.get(rankingRequest, sharedType, actionType);
        ParameterizedTypeName responseMap = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                featureStoreResponse);
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        ParameterizedTypeName actionList = ParameterizedTypeName.get(ClassName.get(List.class), actionType);
        ParameterizedTypeName transformed = ParameterizedTypeName.get(
                ClassName.get("com.hotvect.api.data.ranking", "TransformedAction"), actionType);
        ParameterizedTypeName streamType = ParameterizedTypeName.get(ClassName.get("java.util.stream", "Stream"), transformed);

        MethodSpec.Builder method = MethodSpec.methodBuilder("transformStream")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(streamType)
                .addParameter(requestType, "request");
        method.addStatement("$T featureStoreResponses = featureStoreRetriever.fetch(request)", responseMap);
        method.addStatement("$T context = new $T<>(request.shared(), featureStoreResponses)", contextType, sharedContext);
        method.addStatement("$T actions = request.availableActions()", actionList);
        method.beginControlFlow("if (actions.isEmpty())");
        method.addStatement("return $T.empty()", ClassName.get("java.util.stream", "Stream"));
        method.endControlFlow();
        method.addStatement("SharedValues sharedValues = computeSharedValues(context)");
        method.addStatement("return actions.stream().map(action -> transformAction(context, sharedValues, action))");
        return method.build();
    }

    private MethodSpec buildTransformBatch(TypeName sharedType,
                                           TypeName actionType,
                                           ClassName sharedContext,
                                           ClassName rankingRequest,
                                           ClassName featureStoreResponse,
                                           ClassName listBatchingSpliterator) {
        ParameterizedTypeName requestType = ParameterizedTypeName.get(rankingRequest, sharedType, actionType);
        ParameterizedTypeName responseMap = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                featureStoreResponse);
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        ParameterizedTypeName actionList = ParameterizedTypeName.get(ClassName.get(List.class), actionType);
        ParameterizedTypeName transformed = ParameterizedTypeName.get(
                ClassName.get("com.hotvect.api.data.ranking", "TransformedAction"), actionType);
        ParameterizedTypeName batchStream = ParameterizedTypeName.get(
                ClassName.get("java.util.stream", "Stream"),
                ParameterizedTypeName.get(ClassName.get(List.class), transformed));

        MethodSpec.Builder method = MethodSpec.methodBuilder("transformBatchStream")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(batchStream)
                .addParameter(requestType, "request");
        method.addStatement("$T featureStoreResponses = featureStoreRetriever.fetch(request)", responseMap);
        method.addStatement("$T context = new $T<>(request.shared(), featureStoreResponses)", contextType, sharedContext);
        method.addStatement("$T actions = request.availableActions()", actionList);
        method.beginControlFlow("if (actions.isEmpty())");
        method.addStatement("return $T.empty()", ClassName.get("java.util.stream", "Stream"));
        method.endControlFlow();
        method.addStatement("SharedValues sharedValues = computeSharedValues(context)");
        CodeBlock.Builder chain = CodeBlock.builder();
        chain.add("return $T.stream(new $T<>(actions), false)\n",
                ClassName.get("java.util.stream", "StreamSupport"), listBatchingSpliterator);
        chain.add("        .map(batch -> {\n");
        chain.add("            $T transformed = new $T<>(batch.size());\n",
                ParameterizedTypeName.get(ClassName.get(List.class), transformed), ClassName.get(ArrayList.class));
        chain.add("            for ($T action : batch) {\n", actionType);
        chain.add("                transformed.add(transformAction(context, sharedValues, action));\n");
        chain.add("            }\n");
        chain.add("            return transformed;\n");
        chain.add("        });\n");
        method.addCode(chain.build());
        return method.build();
    }

    private MethodSpec buildGetUsedFeatures(ClassName namespace) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("getUsedFeatures")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(SortedSet.class), namespace));
        method.addStatement("return USED_FEATURES");
        return method.build();
    }

    private MethodSpec buildComputeShared(List<FeatureNode> sharedOrder,
                                          Map<String, String> sharedFields,
                                          Map<String, FeatureNode> nodesByName,
                                          TypeName sharedType,
                                          ClassName sharedContext) {
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        MethodSpec.Builder method = MethodSpec.methodBuilder("computeSharedValues")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ClassName.bestGuess("SharedValues"))
                .addParameter(contextType, "context");
        method.addStatement("$T shared = context.shared()", sharedType);
        method.addStatement("SharedValues sharedValues = new SharedValues()");
        for (FeatureNode node : sharedOrder) {
            CodeBlock expr = buildCallExpression(node, "context", "shared", "action", "sharedValues", "actionValues",
                    sharedFields, Map.of(), nodesByName);
            String field = sharedFields.get(node.name());
            method.addStatement("sharedValues.$L = $L", field, expr);
        }
        method.addStatement("return sharedValues");
        return method.build();
    }

    private MethodSpec buildComputeAction(List<FeatureNode> actionOrder,
                                          Map<String, String> sharedFields,
                                          Map<String, String> actionFields,
                                          Map<String, FeatureNode> nodesByName,
                                          TypeName sharedType,
                                          TypeName actionType,
                                          ClassName sharedContext) {
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        MethodSpec.Builder method = MethodSpec.methodBuilder("computeActionValues")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ClassName.bestGuess("ActionValues"))
                .addParameter(contextType, "context")
                .addParameter(sharedType, "shared")
                .addParameter(actionType, "action")
                .addParameter(ClassName.bestGuess("SharedValues"), "sharedValues");
        method.addStatement("ActionValues actionValues = new ActionValues()");
        for (FeatureNode node : actionOrder) {
            CodeBlock expr = buildCallExpression(node, "context", "shared", "action", "sharedValues", "actionValues",
                    sharedFields, actionFields, nodesByName);
            String field = actionFields.get(node.name());
            method.addStatement("actionValues.$L = $L", field, expr);
        }
        method.addStatement("return actionValues");
        return method.build();
    }

    private MethodSpec buildTransformAction(TypeName actionType,
                                            TypeName sharedType,
                                            ClassName sharedContext,
                                            ClassName transformedAction,
                                            ClassName namespacedRecord,
                                            ClassName namespace,
                                            List<FeatureNode> outputNodes,
                                            Map<String, String> outputConstants,
                                            Map<String, String> actionFields) {
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        ParameterizedTypeName transformedType = ParameterizedTypeName.get(transformedAction, actionType);
        ParameterizedTypeName namespaceMap = ParameterizedTypeName.get(ClassName.get(IdentityHashMap.class), namespace, TypeName.get(Object.class));
        MethodSpec.Builder method = MethodSpec.methodBuilder("transformAction")
                .addModifiers(Modifier.PRIVATE)
                .returns(transformedType)
                .addParameter(contextType, "context")
                .addParameter(ClassName.bestGuess("SharedValues"), "sharedValues")
                .addParameter(actionType, "action");
        method.addStatement("ActionValues actionValues = computeActionValues(context, context.shared(), action, sharedValues)");
        method.addStatement("$T transformations = new $T<>($L)",
                namespaceMap, ClassName.get(IdentityHashMap.class), outputNodes.size() * 2);
        for (FeatureNode node : outputNodes) {
            String constant = outputConstants.get(node.name());
            String field = actionFields.get(node.name());
            method.addStatement("transformations.put($L, actionValues.$L)", constant, field);
        }
        method.addStatement("return $T.of(action, new $T<>(transformations))", transformedAction, namespacedRecord);
        return method.build();
    }

    private CodeBlock buildCallExpression(FeatureNode node,
                                          String contextVar,
                                          String sharedVar,
                                          String actionVar,
                                          String sharedValuesVar,
                                          String actionValuesVar,
                                          Map<String, String> sharedFields,
                                          Map<String, String> actionFields,
                                          Map<String, FeatureNode> nodesByName) {
        TypeElement ownerElement = (TypeElement) node.method().getEnclosingElement();
        ClassName owner = ClassName.get(ownerElement);
        String methodName = node.method().getSimpleName().toString();
        List<String> args = new ArrayList<>();
        for (Param param : node.params()) {
            args.add(paramExpression(param, contextVar, sharedVar, actionVar, sharedValuesVar, actionValuesVar,
                    sharedFields, actionFields, nodesByName));
        }
        CodeBlock.Builder expr = CodeBlock.builder();
        expr.add("$T.$L(", owner, methodName);
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                expr.add(", ");
            }
            expr.add("$L", args.get(i));
        }
        expr.add(")");
        return expr.build();
    }

    private String paramExpression(Param param,
                                   String contextVar,
                                   String sharedVar,
                                   String actionVar,
                                   String sharedValuesVar,
                                   String actionValuesVar,
                                   Map<String, String> sharedFields,
                                   Map<String, String> actionFields,
                                   Map<String, FeatureNode> nodesByName) {
        return switch (param.kind()) {
            case CONTEXT -> contextVar;
            case SHARED -> sharedVar;
            case ACTION -> actionVar;
            case INJECTED -> injectedExpression(param, sharedValuesVar, actionValuesVar, sharedFields, actionFields, nodesByName);
        };
    }

    private String injectedExpression(Param param,
                                      String sharedValuesVar,
                                      String actionValuesVar,
                                      Map<String, String> sharedFields,
                                      Map<String, String> actionFields,
                                      Map<String, FeatureNode> nodesByName) {
        FeatureNode provider = nodesByName.get(param.injectName());
        if (provider != null && provider.kind() == FeatureKind.ACTION) {
            String field = actionFields.get(provider.name());
            if (field == null) {
                field = toFieldName(provider.name());
            }
            return actionValuesVar + "." + field;
        }
        String sharedName = provider == null ? param.injectName() : provider.name();
        String field = sharedFields.get(sharedName);
        if (field == null) {
            field = toFieldName(sharedName);
        }
        return sharedValuesVar + "." + field;
    }

    private TypeSpec buildHolder(String className, List<FeatureNode> order, Map<String, String> fields) {
        TypeSpec.Builder holder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        for (FeatureNode node : order) {
            String field = fields.get(node.name());
            holder.addField(FieldSpec.builder(TypeName.get(node.returnType()), field, Modifier.PRIVATE).build());
        }
        return holder.build();
    }

    private Map<String, String> buildFieldNames(List<FeatureNode> nodes) {
        Map<String, String> fields = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        List<FeatureNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing(FeatureNode::name));
        for (FeatureNode node : sorted) {
            String base = toFieldName(node.name());
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate)) {
                candidate = base + "_" + suffix++;
            }
            fields.put(node.name(), candidate);
        }
        return fields;
    }

    private String toFieldName(String name) {
        List<String> parts = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                token.append(ch);
            } else if (!token.isEmpty()) {
                parts.add(token.toString());
                token.setLength(0);
            }
        }
        if (!token.isEmpty()) {
            parts.add(token.toString());
        }
        if (parts.isEmpty()) {
            parts.add("value");
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (part.isEmpty()) {
                continue;
            }
            if (i == 0) {
                result.append(part.substring(0, 1).toLowerCase(Locale.ROOT));
                if (part.length() > 1) {
                    result.append(part.substring(1));
                }
            } else {
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                if (part.length() > 1) {
                    result.append(part.substring(1));
                }
            }
        }
        String candidate = result.isEmpty() ? "value" : result.toString();
        if (!Character.isLetter(candidate.charAt(0)) && candidate.charAt(0) != '_') {
            candidate = "f_" + candidate;
        }
        if (isJavaKeyword(candidate)) {
            candidate = candidate + "_";
        }
        return candidate;
    }

    private boolean isJavaKeyword(String value) {
        return switch (value) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                 "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                 "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
                 "int", "interface", "long", "native", "new", "package", "private", "protected",
                 "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
                 "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "record",
                 "sealed", "non-sealed", "permits" -> true;
            default -> false;
        };
    }

    private void writeSourceFile(TypeElement specElement, String packageName, String className, JavaFile javaFile) {
        String qualified = packageName == null || packageName.isBlank()
                ? className
                : packageName + "." + className;
        Filer filer = context.filer();
        try {
            JavaFileObject file = filer.createSourceFile(qualified, specElement);
            try (Writer writer = file.openWriter()) {
                javaFile.writeTo(writer);
            }
        } catch (IOException ex) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated transformer: " + ex.getMessage(), specElement);
        }
    }
}
