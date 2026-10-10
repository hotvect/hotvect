package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Plans one complete dependency graph before constructing any owning node. */
final class AlgorithmGraphResolver {

    interface Provider {
        AlgorithmDefinition readDefinition(String algorithmName);

        ClassLoader classLoader();

        AlgorithmInstanceFactory factory();

        File parameterFile();
    }

    interface ProviderCatalog {
        Provider rootProvider(AlgorithmDefinition rootDefinition);

        Provider privateProvider(Provider parent, AlgorithmDependencyDeclaration.Private declaration);

        Provider sharedProvider(AlgorithmDependencyDeclaration.Shared declaration);
    }

    private final ProviderCatalog providerCatalog;
    private final AlgorithmDependencies hostBindings;
    private final AlgorithmGraphDependencies slotBindings;
    private final boolean requireSlotBindings;
    private final boolean traverseSharedNodes;
    private final Set<String> encounteredSlotNames = new LinkedHashSet<>();
    private final Set<String> usedSlotBindings = new LinkedHashSet<>();
    private final LinkedHashMap<NodeAddress, String> activePath = new LinkedHashMap<>();
    private final Map<AlgorithmId, PlannedNode> sharedNodes = new HashMap<>();
    private final LinkedHashMap<AlgorithmId, AlgorithmDefinition> packagedDefinitions = new LinkedHashMap<>();
    private final LinkedHashSet<AlgorithmId> sharedDependencies = new LinkedHashSet<>();

    private AlgorithmGraphResolver(
            ProviderCatalog providerCatalog,
            AlgorithmDependencies hostBindings,
            AlgorithmGraphDependencies slotBindings,
            boolean requireSlotBindings,
            boolean traverseSharedNodes) {
        this.providerCatalog = Objects.requireNonNull(providerCatalog, "providerCatalog must not be null");
        this.hostBindings = Objects.requireNonNull(hostBindings, "hostBindings must not be null");
        this.slotBindings = Objects.requireNonNull(slotBindings, "slotBindings must not be null");
        this.requireSlotBindings = requireSlotBindings;
        this.traverseSharedNodes = traverseSharedNodes;
    }

    /** Inspects declarations through the same traversal used to plan graph construction. */
    static AlgorithmGraphInspection inspect(
            ProviderCatalog providerCatalog,
            AlgorithmDefinition rootDefinition,
            AlgorithmDependencies hostBindings) {
        GraphPlan plan = plan(
                providerCatalog,
                rootDefinition,
                hostBindings,
                AlgorithmGraphDependencies.empty(),
                false,
                true);
        return new AlgorithmGraphInspection(
                plan.root().definition(),
                plan.reachableSlotNames(),
                plan.packagedDefinitions(),
                plan.sharedDependencies());
    }

    /** Inspects shared declarations reachable through private nodes without entering shared subgraphs. */
    static AlgorithmGraphInspection inspectSharedDependencies(
            ProviderCatalog providerCatalog,
            AlgorithmDefinition rootDefinition,
            AlgorithmDependencies hostBindings) {
        GraphPlan plan = plan(
                providerCatalog,
                rootDefinition,
                hostBindings,
                AlgorithmGraphDependencies.empty(),
                false,
                false);
        return new AlgorithmGraphInspection(
                plan.root().definition(),
                plan.reachableSlotNames(),
                plan.packagedDefinitions(),
                plan.sharedDependencies());
    }

    /** Resolves and constructs a graph with application and slot bindings in separate namespaces. */
    static <ALGO extends Algorithm> AlgorithmGraph<ALGO> resolve(
            ProviderCatalog providerCatalog,
            AlgorithmDefinition rootDefinition,
            AlgorithmDependencies hostBindings,
            AlgorithmGraphDependencies slotBindings,
            ExecutionContext executionContext) {
        return resolve(providerCatalog, rootDefinition, hostBindings, slotBindings, null, executionContext);
    }

    /** Plans once, then constructs the exact plan using the serving runtime's shared-node interner. */
    static <ALGO extends Algorithm> AlgorithmGraph<ALGO> resolve(
            ProviderCatalog providerCatalog,
            AlgorithmDefinition rootDefinition,
            AlgorithmDependencies hostBindings,
            AlgorithmGraphDependencies slotBindings,
            SharedNodeInterner sharedNodeInterner,
            ExecutionContext executionContext) {
        GraphPlan plan = plan(providerCatalog, rootDefinition, hostBindings, slotBindings, true, true);
        return new GraphConstructor(sharedNodeInterner, hostBindings, executionContext).constructGraph(plan);
    }

    static <DEPENDENCY> ResolvedFeatureExtraction<DEPENDENCY> resolveFeatureExtraction(
            ProviderCatalog providerCatalog,
            AlgorithmDefinition rootDefinition,
            AlgorithmDependencies hostBindings,
            ExecutionContext executionContext) {
        GraphPlan plan = plan(
                providerCatalog,
                rootDefinition,
                hostBindings,
                AlgorithmGraphDependencies.empty(),
                true,
                true);
        RuntimeGraphBuilder graphBuilder = new RuntimeGraphBuilder();
        try {
            GraphConstructor constructor = new GraphConstructor(null, hostBindings, executionContext);
            ResolvedDependencies dependencies = constructor.constructDependencies(plan.root(), graphBuilder);
            DEPENDENCY dependency = plan.root().provider().factory().constructFeatureExtractionDependency(
                    plan.root().definition(),
                    plan.root().provider().parameterFile(),
                    dependencies.instances(),
                    hostBindings,
                    executionContext);
            return new ResolvedFeatureExtraction<>(dependency, graphBuilder.build());
        } catch (RuntimeException | Error failure) {
            graphBuilder.closeAfterFailure(failure);
            throw failure;
        }
    }

    record ResolvedFeatureExtraction<DEPENDENCY>(
            DEPENDENCY dependency,
            RuntimeGraphOwnership ownership) {
        ResolvedFeatureExtraction {
            Objects.requireNonNull(ownership, "ownership must not be null");
        }
    }

    private static GraphPlan plan(
            ProviderCatalog providerCatalog,
            AlgorithmDefinition rootDefinition,
            AlgorithmDependencies hostBindings,
            AlgorithmGraphDependencies slotBindings,
            boolean requireSlotBindings,
            boolean traverseSharedNodes) {
        AlgorithmDefinition resolvedRoot = Objects.requireNonNull(rootDefinition, "rootDefinition must not be null");
        ProviderCatalog resolvedCatalog = Objects.requireNonNull(providerCatalog, "providerCatalog must not be null");
        Provider rootProvider = Objects.requireNonNull(
                resolvedCatalog.rootProvider(resolvedRoot),
                "root provider must not be null");
        AlgorithmGraphResolver planner = new AlgorithmGraphResolver(
                resolvedCatalog,
                hostBindings,
                slotBindings,
                requireSlotBindings,
                traverseSharedNodes);
        planner.packagedDefinitions.put(resolvedRoot.algorithmId(), resolvedRoot);
        PlannedNode root = planner.planNode(resolvedRoot, rootProvider, null);
        if (requireSlotBindings) {
            planner.requireEverySlotBindingWasUsed(resolvedRoot);
        }
        return new GraphPlan(
                root,
                Collections.unmodifiableSet(new LinkedHashSet<>(planner.encounteredSlotNames)),
                Collections.unmodifiableMap(new LinkedHashMap<>(planner.packagedDefinitions)),
                Collections.unmodifiableSet(new LinkedHashSet<>(planner.sharedDependencies)));
    }

    private PlannedNode planNode(
            AlgorithmDefinition definition,
            Provider provider,
            AlgorithmId sharedIdentity) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        String algorithmName = definition.algorithmId().algorithmName();
        NodeAddress address = new NodeAddress(provider, algorithmName);
        enter(address, algorithmName);
        try {
            return new PlannedNode(
                    definition,
                    provider,
                    planDependencies(definition, provider),
                    sharedIdentity);
        } finally {
            leave(address);
        }
    }

    private PlannedDependencies planDependencies(
            AlgorithmDefinition parentDefinition,
            Provider parentProvider) {
        LinkedHashMap<String, PlannedSelection> dependencies = new LinkedHashMap<>();
        for (AlgorithmDependencyDeclaration declaration : parentDefinition.dependencyDeclarations().values()) {
            planDependency(parentDefinition.algorithmId().algorithmName(), declaration, parentProvider)
                    .ifPresent(selection -> dependencies.put(declaration.name(), selection));
        }
        return new PlannedDependencies(dependencies);
    }

    private Optional<PlannedSelection> planDependency(
            String parentAlgorithmName,
            AlgorithmDependencyDeclaration declaration,
            Provider parentProvider) {
        if (declaration instanceof AlgorithmDependencyDeclaration.Slot slotDeclaration) {
            if (hostBindings.asMap().containsKey(declaration.name())) {
                throw new IllegalStateException(
                        "Dependency " + parentAlgorithmName + "." + declaration.name()
                                + " is supplied both by the application and EMS slot " + slotDeclaration.name());
            }
            encounteredSlotNames.add(slotDeclaration.name());
            AlgorithmGraphDependencies.Binding slotBinding = slotBindings.asMap().get(slotDeclaration.name());
            if (slotBinding == null) {
                if (requireSlotBindings) {
                    throw slotDependencyRequiresExternalSelection(
                            parentAlgorithmName,
                            declaration.name(),
                            slotDeclaration.name());
                }
                return Optional.empty();
            }
            usedSlotBindings.add(slotDeclaration.name());
            return Optional.of(new SlotSelection(slotBinding));
        }

        AlgorithmInstance<?> hostBinding = hostBindings.asMap().get(declaration.name());
        if (hostBinding != null) {
            return Optional.of(new HostSelection(hostBinding));
        }

        return switch (declaration) {
            case AlgorithmDependencyDeclaration.Private privateDeclaration -> {
                Provider childProvider = providerCatalog.privateProvider(parentProvider, privateDeclaration);
                AlgorithmDefinition childDefinition = childProvider.readDefinition(privateDeclaration.name());
                packagedDefinitions.putIfAbsent(childDefinition.algorithmId(), childDefinition);
                AlgorithmDefinition effectiveDefinition = privateDeclaration.algorithmDefinitionOverride()
                        .map(override -> childProvider.factory().applyDefinitionOverride(childDefinition, override))
                        .orElse(childDefinition);
                PlannedNode child = planNode(effectiveDefinition, childProvider, null);
                yield Optional.of(new OwnedSelection(child));
            }
            case AlgorithmDependencyDeclaration.Shared sharedDeclaration -> {
                Provider childProvider = providerCatalog.sharedProvider(sharedDeclaration);
                AlgorithmDefinition childDefinition = childProvider.readDefinition(sharedDeclaration.name());
                requireMatchingSharedAlgorithmId(sharedDeclaration, childDefinition.algorithmId());
                packagedDefinitions.putIfAbsent(childDefinition.algorithmId(), childDefinition);
                sharedDependencies.add(sharedDeclaration.algorithmId());
                if (!traverseSharedNodes) {
                    yield Optional.empty();
                }
                PlannedNode child = sharedNodes.get(sharedDeclaration.algorithmId());
                if (child == null) {
                    child = planNode(childDefinition, childProvider, sharedDeclaration.algorithmId());
                    sharedNodes.put(sharedDeclaration.algorithmId(), child);
                }
                yield Optional.of(new OwnedSelection(child));
            }
            case AlgorithmDependencyDeclaration.Slot ignored -> throw new IllegalStateException(
                    "Slot dependency should have been planned before ordinary dependency traversal");
        };
    }

    private static IllegalStateException slotDependencyRequiresExternalSelection(
            String algorithmName,
            String dependencyName,
            String slot) {
        return new IllegalStateException(
                "Slot-backed dependency " + algorithmName + "." + dependencyName
                        + " requires an externally selected algorithm for EMS slot " + slot);
    }

    private static void requireMatchingSharedAlgorithmId(
            AlgorithmDependencyDeclaration.Shared declaration,
            AlgorithmId resolvedAlgorithmId) {
        if (!declaration.algorithmId().equals(resolvedAlgorithmId)) {
            throw new IllegalArgumentException(
                    "Dependency " + declaration.name() + " requires " + declaration.algorithmId()
                            + " but resolved " + resolvedAlgorithmId);
        }
    }

    private void enter(NodeAddress address, String algorithmName) {
        if (activePath.containsKey(address)) {
            List<String> names = new ArrayList<>(activePath.values());
            int cycleStart = new ArrayList<>(activePath.keySet()).indexOf(address);
            List<String> cycle = new ArrayList<>(names.subList(cycleStart, names.size()));
            cycle.add(algorithmName);
            throw new IllegalArgumentException("Cyclic algorithm dependency: " + String.join(" -> ", cycle));
        }
        activePath.put(address, algorithmName);
    }

    private void leave(NodeAddress address) {
        activePath.remove(address);
    }

    private void requireEverySlotBindingWasUsed(AlgorithmDefinition rootDefinition) {
        if (usedSlotBindings.size() == slotBindings.names().size()) {
            return;
        }
        Set<String> unknown = new TreeSet<>(slotBindings.names());
        unknown.removeAll(encounteredSlotNames);
        Set<String> unreachable = new TreeSet<>(slotBindings.names());
        unreachable.removeAll(usedSlotBindings);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "EMS slot bindings " + unknown
                            + " are not declared anywhere reachable from "
                            + rootDefinition.algorithmId().algorithmName());
        }
        throw new IllegalArgumentException(
                "EMS slot bindings " + unreachable
                        + " are not active in the resolved graph for "
                        + rootDefinition.algorithmId().algorithmName());
    }

    private record GraphPlan(
            PlannedNode root,
            Set<String> reachableSlotNames,
            Map<AlgorithmId, AlgorithmDefinition> packagedDefinitions,
            Set<AlgorithmId> sharedDependencies) {
    }

    private record PlannedNode(
            AlgorithmDefinition definition,
            Provider provider,
            PlannedDependencies dependencies,
            AlgorithmId sharedIdentity) {
        private PlannedNode {
            Objects.requireNonNull(definition, "definition must not be null");
            Objects.requireNonNull(provider, "provider must not be null");
            Objects.requireNonNull(dependencies, "dependencies must not be null");
        }
    }

    private record PlannedDependencies(Map<String, PlannedSelection> selections) {
        private PlannedDependencies {
            selections = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(selections, "selections must not be null")));
        }
    }

    private sealed interface PlannedSelection permits OwnedSelection, HostSelection, SlotSelection {
    }

    private record OwnedSelection(PlannedNode node) implements PlannedSelection {
        private OwnedSelection {
            Objects.requireNonNull(node, "node must not be null");
        }
    }

    private record HostSelection(AlgorithmInstance<?> instance) implements PlannedSelection {
        private HostSelection {
            Objects.requireNonNull(instance, "instance must not be null");
        }
    }

    private record SlotSelection(AlgorithmGraphDependencies.Binding binding) implements PlannedSelection {
        private SlotSelection {
            Objects.requireNonNull(binding, "binding must not be null");
        }
    }

    private record NodeAddress(Provider provider, String algorithmName) {
        private NodeAddress {
            Objects.requireNonNull(provider, "provider must not be null");
            Objects.requireNonNull(algorithmName, "algorithmName must not be null");
        }
    }

    private static final class GraphConstructor {
        private final SharedNodeInterner sharedNodeInterner;
        private final AlgorithmDependencies applicationBindings;
        private final ExecutionContext executionContext;
        private final Map<RuntimeGraphBuilder, Map<PlannedNode, ResolvedNode>> constructedSharedNodes =
                new IdentityHashMap<>();

        private GraphConstructor(
                SharedNodeInterner sharedNodeInterner,
                AlgorithmDependencies applicationBindings,
                ExecutionContext executionContext) {
            this.sharedNodeInterner = sharedNodeInterner;
            this.applicationBindings = Objects.requireNonNull(
                    applicationBindings,
                    "applicationBindings must not be null");
            this.executionContext = Objects.requireNonNull(
                    executionContext,
                    "executionContext must not be null");
        }

        private <ALGO extends Algorithm> AlgorithmGraph<ALGO> constructGraph(GraphPlan plan) {
            RuntimeGraphBuilder graphBuilder = new RuntimeGraphBuilder();
            try {
                ResolvedNode root = constructNode(plan.root(), graphBuilder);
                @SuppressWarnings("unchecked")
                AlgorithmInstance<ALGO> typedRoot = (AlgorithmInstance<ALGO>) root.instance();
                return new AlgorithmGraph<>(
                        typedRoot,
                        root.runtimeId(),
                        plan.root().provider().classLoader(),
                        graphBuilder.build());
            } catch (RuntimeException | Error failure) {
                graphBuilder.closeAfterFailure(failure);
                throw failure;
            }
        }

        private ResolvedNode constructNode(
                PlannedNode node,
                RuntimeGraphBuilder graphBuilder) {
            if (node.sharedIdentity() == null) {
                return constructOwnedNode(node, graphBuilder);
            }
            Map<PlannedNode, ResolvedNode> nodesForGraph =
                    constructedSharedNodes.computeIfAbsent(graphBuilder, ignored -> new HashMap<>());
            ResolvedNode existing = nodesForGraph.get(node);
            if (existing != null) {
                return existing;
            }
            ResolvedNode constructed;
            if (sharedNodeInterner == null) {
                constructed = constructOwnedNode(node, graphBuilder);
            } else {
                SharedNodeInterner.SharedNode sharedNode = sharedNodeInterner.intern(
                        node.sharedIdentity(),
                        () -> constructSharedGraph(node));
                graphBuilder.addOwnedResource(sharedNode);
                constructed = new ResolvedNode(sharedNode.instance(), node.provider(), sharedNode.runtimeId());
            }
            nodesForGraph.put(node, constructed);
            return constructed;
        }

        private ResolvedNode constructOwnedNode(
                PlannedNode node,
                RuntimeGraphBuilder graphBuilder) {
            ResolvedDependencies dependencies = constructDependencies(node, graphBuilder);
            AlgorithmDependencies resolvedDependencies = dependencies.instances();
            AlgorithmInstance<?> instance = node.provider().factory().construct(
                    node.definition(),
                    node.provider().parameterFile(),
                    resolvedDependencies,
                    applicationBindings,
                    executionContext);
            requireOwnedResult(instance, resolvedDependencies);
            requireOwnedResult(instance, applicationBindings);
            graphBuilder.addOwnedNode(instance);
            return new ResolvedNode(
                    instance,
                    node.provider(),
                    AlgorithmRuntimeId.from(instance, dependencies.runtimeIds()));
        }

        private static void requireOwnedResult(
                AlgorithmInstance<?> instance,
                AlgorithmDependencies dependencies) {
            dependencies.asMap().forEach((name, dependency) -> {
                if (instance.algorithm() == dependency.algorithm()) {
                    throw new IllegalArgumentException(
                            "Factory for " + instance.algorithmDefinition().algorithmId()
                                    + " returned dependency " + name
                                    + "; factories must return a newly owned algorithm instance");
                }
            });
        }

        private ResolvedDependencies constructDependencies(
                PlannedNode parent,
                RuntimeGraphBuilder graphBuilder) {
            TreeMap<String, ResolvedNode> dependencies = new TreeMap<>();
            parent.dependencies().selections().forEach((dependencyName, selection) -> {
                ResolvedNode child = constructSelection(selection, graphBuilder);
                validateBoundary(parent.definition(), parent.provider(), dependencyName, child);
                dependencies.put(dependencyName, child);
            });
            return new ResolvedDependencies(dependencies);
        }

        private ResolvedNode constructSelection(
                PlannedSelection selection,
                RuntimeGraphBuilder graphBuilder) {
            return switch (selection) {
                case OwnedSelection owned -> constructNode(owned.node(), graphBuilder);
                case HostSelection host -> new ResolvedNode(
                        host.instance(),
                        null,
                        AlgorithmRuntimeId.from(host.instance(), Map.of()));
                case SlotSelection slot -> {
                    graphBuilder.retain(slot.binding().graph());
                    yield new ResolvedNode(
                            slot.binding().instance(),
                            null,
                            slot.binding().runtimeId());
                }
            };
        }

        private AlgorithmGraph<?> constructSharedGraph(PlannedNode node) {
            RuntimeGraphBuilder graphBuilder = new RuntimeGraphBuilder();
            try {
                ResolvedNode root = constructOwnedNode(node, graphBuilder);
                return graphFor(root, graphBuilder);
            } catch (RuntimeException | Error failure) {
                graphBuilder.closeAfterFailure(failure);
                throw failure;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static AlgorithmGraph<?> graphFor(
                ResolvedNode root,
                RuntimeGraphBuilder graphBuilder) {
            return new AlgorithmGraph(
                    (AlgorithmInstance) root.instance(),
                    root.runtimeId(),
                    root.provider().classLoader(),
                    graphBuilder.build());
        }

        private static void validateBoundary(
                AlgorithmDefinition parentDefinition,
                Provider parentProvider,
                String dependencyName,
                ResolvedNode child) {
            if (child.provider() == null && hasNoAlgorithmFactory(child.instance())) {
                return;
            }
            DomainModelBoundaryValidator.validateEdge(
                    parentDefinition.algorithmId().algorithmName(),
                    parentProvider.classLoader(),
                    dependencyName,
                    child.instance().algorithmType());
        }

        private static boolean hasNoAlgorithmFactory(AlgorithmInstance<?> instance) {
            String factoryName = instance.algorithmDefinition().algorithmFactoryName();
            return factoryName == null || factoryName.isBlank();
        }
    }

    private record ResolvedNode(
            AlgorithmInstance<?> instance,
            Provider provider,
            AlgorithmRuntimeId runtimeId) {
        private ResolvedNode {
            Objects.requireNonNull(instance, "instance must not be null");
            Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        }
    }

    private record ResolvedDependencies(Map<String, ResolvedNode> nodes) {
        private ResolvedDependencies {
            nodes = Map.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        }

        private AlgorithmDependencies instances() {
            if (nodes.isEmpty()) {
                return AlgorithmDependencies.empty();
            }
            TreeMap<String, AlgorithmInstance<?>> instances = new TreeMap<>();
            nodes.forEach((dependencyName, node) -> instances.put(dependencyName, node.instance()));
            return new AlgorithmDependencies(instances);
        }

        private Map<String, AlgorithmRuntimeId> runtimeIds() {
            TreeMap<String, AlgorithmRuntimeId> runtimeIds = new TreeMap<>();
            nodes.forEach((dependencyName, node) -> runtimeIds.put(dependencyName, node.runtimeId()));
            return runtimeIds;
        }
    }
}
