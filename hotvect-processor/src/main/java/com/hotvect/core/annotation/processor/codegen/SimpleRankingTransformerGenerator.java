package com.hotvect.core.annotation.processor.codegen;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.hotvect.core.annotation.backend.GeneratedTransformerBackend;
import com.hotvect.core.annotation.backend.Resolution;
import com.hotvect.core.annotation.processor.ProcessingContext;
import com.hotvect.core.annotation.processor.model.Analysis;
import com.hotvect.core.annotation.processor.model.FeatureSpec;
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
        Map<String, AlgorithmDependency> algorithmDependencies = resolveAlgorithmDependencies(reachable);
        if (algorithmDependencies == null) {
            return;
        }

        GeneratedTransformerBackend backend = loadGeneratedTransformerBackend(spec.backend(), specElement);
        if (backend == null) {
            return;
        }

        List<FeatureNode> outputNodes = new ArrayList<>();
        Map<String, FeatureSpec> outputSpecs = new LinkedHashMap<>();
        for (FeatureSpec featureSpec : spec.outputFeatures()) {
            FeatureNode node = nodesByName.get(featureSpec.name());
            if (node != null) {
                outputNodes.add(node);
                outputSpecs.put(node.name(), featureSpec);
            }
        }
        boolean outputTypeErrors = false;
        Map<String, String> outputInitializers = new LinkedHashMap<>();
        for (FeatureNode node : outputNodes) {
            FeatureSpec featureSpec = outputSpecs.get(node.name());
            Resolution resolution;
            try {
                resolution = backend.resolve(featureSpec.type(), returnTypeName(node.returnType()));
            } catch (RuntimeException e) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "Generated transformer backend '" + spec.backend() + "' failed to resolve feature '"
                                + node.name() + "': " + e,
                        node.method());
                outputTypeErrors = true;
                continue;
            }
            if (resolution.isError()) {
                context.messager().printMessage(Diagnostic.Kind.ERROR, resolution.error(), node.method());
                outputTypeErrors = true;
            } else {
                outputInitializers.put(node.name(), resolution.initializerExpression());
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
        ClassName namespaces = ClassName.get("com.hotvect.core.transform", "Namespaces");
        ClassName listBatchingSpliterator = ClassName.get("com.hotvect.core.transform.ranking", "ListBatchingSpliterator");
        ClassName featureStoreRetriever = ClassName.get("com.hotvect.core.featurestore", "FeatureStoreRetriever");

        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(streamingTransformer, sharedType, actionType));

        for (FeatureNode node : outputNodes) {
            String constant = outputConstants.get(node.name());
            String initializer = outputInitializers.get(node.name());
            FieldSpec field = FieldSpec.builder(namespace, constant, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.declareFeatureNamespace($L, $S)", namespaces, initializer, node.name())
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
        for (AlgorithmDependency algorithmDependency : algorithmDependencies.values()) {
            typeBuilder.addField(FieldSpec.builder(TypeName.get(algorithmDependency.type()), algorithmDependency.fieldName(),
                    Modifier.PRIVATE, Modifier.FINAL).build());
        }

        typeBuilder.addMethod(buildPrimaryConstructor(featureStoreRetrieverType, algorithmDependencies));

        typeBuilder.addMethod(buildTransformStream(sharedType, actionType, sharedContext, rankingRequest,
                featureStoreResponse));
        typeBuilder.addMethod(buildPrepareBatchStream(sharedType, actionType, sharedContext, rankingRequest,
                featureStoreResponse, listBatchingSpliterator));
        typeBuilder.addMethod(buildGetUsedFeatures(namespace));

        typeBuilder.addMethod(buildComputeShared(sharedOrder, sharedFields, nodesByName, sharedType, sharedContext, algorithmDependencies));
        typeBuilder.addMethod(buildComputeAction(actionOrder, sharedFields, actionFields, nodesByName,
                sharedType, actionType, sharedContext, algorithmDependencies));
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

    private GeneratedTransformerBackend loadGeneratedTransformerBackend(TypeMirror backendType, TypeElement specElement) {
        if (backendType == null) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "@GenerateSimpleRankingTransformer.backend must be a GeneratedTransformerBackend class.",
                    specElement);
            return null;
        }
        String backendClassName = backendType.toString();
        try {
            Class<?> backendClass = Class.forName(backendClassName, false, getClass().getClassLoader());
            if (!GeneratedTransformerBackend.class.isAssignableFrom(backendClass)) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "@GenerateSimpleRankingTransformer.backend '" + backendClassName + "' does not implement "
                                + GeneratedTransformerBackend.class.getName() + ".",
                        specElement);
                return null;
            }
            return backendClass.asSubclass(GeneratedTransformerBackend.class).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Generated transformer backend '" + backendClassName
                            + "' was not found on the annotation processor path. Add the backend module"
                            + " (e.g. hotvect-catboost or hotvect-tensorflow) as an annotation processor dependency.",
                    specElement);
            return null;
        } catch (ReflectiveOperationException e) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to instantiate generated transformer backend '" + backendClassName + "': " + e,
                    specElement);
            return null;
        }
    }

    private String returnTypeName(TypeMirror returnType) {
        return context.types().erasure(returnType).toString();
    }


    private MethodSpec buildPrimaryConstructor(TypeName featureStoreRetrieverType,
                                              Map<String, AlgorithmDependency> algorithmDependencies) {
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(featureStoreRetrieverType, "featureStoreRetriever");
        ctor.addStatement("this.featureStoreRetriever = featureStoreRetriever");
        for (AlgorithmDependency algorithmDependency : algorithmDependencies.values()) {
            ctor.addParameter(TypeName.get(algorithmDependency.type()), algorithmDependency.fieldName());
            ctor.addStatement("this.$L = $L", algorithmDependency.fieldName(), algorithmDependency.fieldName());
        }
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
        ClassName availableAction = ClassName.get("com.hotvect.api.data", "AvailableAction");
        ParameterizedTypeName actionList = ParameterizedTypeName.get(ClassName.get(List.class),
                ParameterizedTypeName.get(availableAction, actionType));
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
        method.addStatement("$T actions = request.actions()", actionList);
        method.beginControlFlow("if (actions.isEmpty())");
        method.addStatement("return $T.empty()", ClassName.get("java.util.stream", "Stream"));
        method.endControlFlow();
        method.addStatement("SharedValues sharedValues = computeSharedValues(context)");
        method.addStatement("return actions.stream().map(action -> transformAction(context, sharedValues, action))");
        return method.build();
    }

    private MethodSpec buildPrepareBatchStream(TypeName sharedType,
                                               TypeName actionType,
                                               ClassName sharedContext,
                                               ClassName rankingRequest,
                                               ClassName featureStoreResponse,
                                               ClassName listBatchingSpliterator) {
        ClassName preparedBatchStream = ClassName.get("com.hotvect.core.transform.ranking", "PreparedBatchStream");
        ParameterizedTypeName requestType = ParameterizedTypeName.get(rankingRequest, sharedType, actionType);
        ParameterizedTypeName responseMap = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                featureStoreResponse);
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        ClassName availableAction = ClassName.get("com.hotvect.api.data", "AvailableAction");
        ParameterizedTypeName actionList = ParameterizedTypeName.get(ClassName.get(List.class),
                ParameterizedTypeName.get(availableAction, actionType));
        ParameterizedTypeName transformed = ParameterizedTypeName.get(
                ClassName.get("com.hotvect.api.data.ranking", "TransformedAction"), actionType);
        ParameterizedTypeName returnType = ParameterizedTypeName.get(preparedBatchStream, actionType);

        MethodSpec.Builder method = MethodSpec.methodBuilder("prepareBatchStream")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addParameter(requestType, "request");
        method.addStatement("$T featureStoreResponses = featureStoreRetriever.fetch(request)", responseMap);
        method.addStatement("$T context = new $T<>(request.shared(), featureStoreResponses)", contextType, sharedContext);
        method.addStatement("$T actions = request.actions()", actionList);
        method.beginControlFlow("if (actions.isEmpty())");
        method.addStatement("return new $T<>($T.empty(), featureStoreResponses)",
                preparedBatchStream, ClassName.get("java.util.stream", "Stream"));
        method.endControlFlow();
        method.addStatement("SharedValues sharedValues = computeSharedValues(context)");
        CodeBlock.Builder chain = CodeBlock.builder();
        chain.add("$T batchStream = $T.stream(new $T<>(actions), false)\n",
                ParameterizedTypeName.get(ClassName.get("java.util.stream", "Stream"),
                        ParameterizedTypeName.get(ClassName.get(List.class), transformed)),
                ClassName.get("java.util.stream", "StreamSupport"), listBatchingSpliterator);
        chain.add("        .map(batch -> {\n");
        chain.add("            $T transformedBatch = new $T<>(batch.size());\n",
                ParameterizedTypeName.get(ClassName.get(List.class), transformed), ClassName.get(ArrayList.class));
        chain.add("            for ($T action : batch) {\n", ParameterizedTypeName.get(availableAction, actionType));
        chain.add("                transformedBatch.add(transformAction(context, sharedValues, action));\n");
        chain.add("            }\n");
        chain.add("            return transformedBatch;\n");
        chain.add("        });\n");
        method.addCode(chain.build());
        method.addStatement("return new $T<>(batchStream, featureStoreResponses)", preparedBatchStream);
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
                                          ClassName sharedContext,
                                          Map<String, AlgorithmDependency> algorithmDependencies) {
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        MethodSpec.Builder method = MethodSpec.methodBuilder("computeSharedValues")
                .addModifiers(Modifier.PRIVATE)
                .returns(ClassName.bestGuess("SharedValues"))
                .addParameter(contextType, "context");
        method.addStatement("$T shared = context.shared()", sharedType);
        method.addStatement("SharedValues sharedValues = new SharedValues()");
        for (FeatureNode node : sharedOrder) {
            CodeBlock expr = buildCallExpression(node, "context", "shared", "action", "sharedValues", "actionValues",
                    sharedFields, Map.of(), nodesByName, algorithmDependencies);
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
                                          ClassName sharedContext,
                                          Map<String, AlgorithmDependency> algorithmDependencies) {
        ParameterizedTypeName contextType = ParameterizedTypeName.get(sharedContext, sharedType);
        MethodSpec.Builder method = MethodSpec.methodBuilder("computeActionValues")
                .addModifiers(Modifier.PRIVATE)
                .returns(ClassName.bestGuess("ActionValues"))
                .addParameter(contextType, "context")
                .addParameter(sharedType, "shared")
                .addParameter(actionType, "action")
                .addParameter(ClassName.bestGuess("SharedValues"), "sharedValues");
        method.addStatement("ActionValues actionValues = new ActionValues()");
        for (FeatureNode node : actionOrder) {
            CodeBlock expr = buildCallExpression(node, "context", "shared", "action", "sharedValues", "actionValues",
                    sharedFields, actionFields, nodesByName, algorithmDependencies);
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
        ParameterizedTypeName availableActionType = ParameterizedTypeName.get(
                ClassName.get("com.hotvect.api.data", "AvailableAction"),
                actionType);
        MethodSpec.Builder method = MethodSpec.methodBuilder("transformAction")
                .addModifiers(Modifier.PRIVATE)
                .returns(transformedType)
                .addParameter(contextType, "context")
                .addParameter(ClassName.bestGuess("SharedValues"), "sharedValues")
                .addParameter(availableActionType, "availableAction");
        method.addStatement("$T action = availableAction.action()", actionType);
        method.addStatement("ActionValues actionValues = computeActionValues(context, context.shared(), action, sharedValues)");
        method.addStatement("$T transformations = new $T<>($L)",
                namespaceMap, ClassName.get(IdentityHashMap.class), outputNodes.size() * 2);
        for (FeatureNode node : outputNodes) {
            String constant = outputConstants.get(node.name());
            String field = actionFields.get(node.name());
            method.addStatement("transformations.put($L, actionValues.$L)", constant, field);
        }
        method.addStatement("return $T.of(availableAction.actionId(), action, new $T<>(transformations))", transformedAction, namespacedRecord);
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
                                          Map<String, FeatureNode> nodesByName,
                                          Map<String, AlgorithmDependency> algorithmDependencies) {
        TypeElement ownerElement = (TypeElement) node.method().getEnclosingElement();
        ClassName owner = ClassName.get(ownerElement);
        String methodName = node.method().getSimpleName().toString();
        List<String> args = new ArrayList<>();
        for (Param param : node.params()) {
            args.add(paramExpression(param, contextVar, sharedVar, actionVar, sharedValuesVar, actionValuesVar,
                    sharedFields, actionFields, nodesByName, algorithmDependencies));
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
                                   Map<String, FeatureNode> nodesByName,
                                   Map<String, AlgorithmDependency> algorithmDependencies) {
        return switch (param.kind()) {
            case CONTEXT -> contextVar;
            case SHARED -> sharedVar;
            case ACTION -> actionVar;
            case INJECTED -> injectedExpression(param, sharedValuesVar, actionValuesVar, sharedFields, actionFields, nodesByName);
            case ALGORITHM -> algorithmExpression(param, algorithmDependencies);
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

    private String algorithmExpression(Param param, Map<String, AlgorithmDependency> algorithmDependencies) {
        AlgorithmDependency algorithmDependency = algorithmDependencies.get(param.injectName());
        if (algorithmDependency == null) {
            return "this." + toFieldName(param.injectName());
        }
        return "this." + algorithmDependency.fieldName();
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

    private Map<String, AlgorithmDependency> resolveAlgorithmDependencies(Set<FeatureNode> reachable) {
        Map<String, AlgorithmDependency> dependencies = new LinkedHashMap<>();
        Set<String> usedFieldNames = new HashSet<>();
        usedFieldNames.add("featureStoreRetriever");
        boolean hasErrors = false;

        List<FeatureNode> ordered = new ArrayList<>(reachable);
        ordered.sort(Comparator.comparing(FeatureNode::name));
        for (FeatureNode node : ordered) {
            for (Param param : node.params()) {
                if (param.kind() != ParamKind.ALGORITHM) {
                    continue;
                }

                AlgorithmDependency existing = dependencies.get(param.injectName());
                if (existing != null) {
                    if (!sameErasure(existing.type(), param.element().asType())) {
                        context.messager().printMessage(
                                Diagnostic.Kind.ERROR,
                                "Algorithm dependency '" + param.injectName() + "' is requested with incompatible parameter types: "
                                        + existing.type() + " and " + param.element().asType() + ".",
                                param.element()
                        );
                        hasErrors = true;
                    }
                    continue;
                }

                String baseFieldName = toFieldName(param.injectName());
                String fieldName = baseFieldName;
                int suffix = 2;
                while (!usedFieldNames.add(fieldName)) {
                    fieldName = baseFieldName + "_" + suffix++;
                }
                dependencies.put(param.injectName(), new AlgorithmDependency(param.injectName(), param.element().asType(), fieldName));
            }
        }

        return hasErrors ? null : dependencies;
    }

    private boolean sameErasure(TypeMirror left, TypeMirror right) {
        return context.types().isSameType(context.types().erasure(left), context.types().erasure(right));
    }

    private record AlgorithmDependency(String injectName, TypeMirror type, String fieldName) {}

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
