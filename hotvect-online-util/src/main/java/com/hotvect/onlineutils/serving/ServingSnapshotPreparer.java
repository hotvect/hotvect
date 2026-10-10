package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.experimentmanagement.ExperimentManagementStateSource;
import com.hotvect.onlineutils.experimentmanagement.experimentation.EmsAlgorithmDefinitionValidator;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.experimentmanagement.variantassignment.VariantAssigner;
import com.hotvect.onlineutils.util.Closeables;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reads EMS state and prepares every bounded root composition before publication. */
final class ServingSnapshotPreparer {
    private static final int MAX_COMPOSITIONS_PER_ROOT = 64;
    private static final int MAX_COMPOSITIONS_PER_RUNTIME = 256;

    private final Set<String> rootSlots;
    private final AlgorithmRepository algorithmRepository;
    private final ExperimentManagementStateSource stateSource;
    private final RootAlgorithmValidator rootAlgorithmValidator;

    ServingSnapshotPreparer(
            Set<String> rootSlots,
            AlgorithmRepository algorithmRepository,
            ExperimentManagementStateSource stateSource,
            RootAlgorithmValidator rootAlgorithmValidator) {
        TreeSet<String> resolvedRootSlots = new TreeSet<>();
        for (String rootSlot : Objects.requireNonNull(rootSlots, "rootSlots must not be null")) {
            if (rootSlot == null || rootSlot.isBlank()) {
                throw new IllegalArgumentException("root slot names must not be blank");
            }
            resolvedRootSlots.add(rootSlot);
        }
        if (resolvedRootSlots.isEmpty()) {
            throw new IllegalArgumentException("At least one root slot is required");
        }
        this.rootSlots = Set.copyOf(resolvedRootSlots);
        this.algorithmRepository = Objects.requireNonNull(
                algorithmRepository,
                "algorithmRepository must not be null");
        this.stateSource = Objects.requireNonNull(stateSource, "stateSource must not be null");
        this.rootAlgorithmValidator = Objects.requireNonNull(
                rootAlgorithmValidator,
                "rootAlgorithmValidator must not be null");
    }

    ServingSnapshot prepareSnapshot(ExecutionContext executionContext) throws Exception {
        return prepareInspected(inspect(), executionContext);
    }

    Preparation inspect() throws Exception {
        Preparation preparation = new Preparation();
        try {
            for (String rootSlotName : rootSlots) {
                loadSlot(rootSlotName, preparation, new LinkedHashSet<>());
            }
            return preparation;
        } catch (Exception | Error failure) {
            Closeables.closeAfterFailure(failure, preparation);
            throw failure;
        }
    }

    ServingSnapshot prepareInspected(Preparation preparation, ExecutionContext executionContext) throws Exception {
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        ServingSnapshot snapshot = null;
        try (preparation;
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = algorithmRepository.sharedArtifactCatalog(
                        preparation.inspections.values())) {
            snapshot = prepareSnapshot(preparation, sharedArtifacts, executionContext);
        } catch (Exception | Error failure) {
            Closeables.closeAfterFailure(failure, snapshot);
            throw failure;
        }
        return snapshot;
    }

    private ServingSnapshot prepareSnapshot(
            Preparation builder,
            AlgorithmRepository.SharedArtifactCatalog sharedArtifacts,
            ExecutionContext executionContext) throws Exception {
        Map<String, List<PreparationContext>> contextsByRoot = new LinkedHashMap<>();
        TreeMap<String, Integer> compositionsByRoot = new TreeMap<>();
        int runtimeCompositionCount = 0;
        for (String rootSlot : rootSlots) {
            ServingSnapshot.SlotState rootState = Objects.requireNonNull(builder.slots.get(rootSlot));
            List<PreparationContext> rootContexts = new ArrayList<>();
            for (Variant rootVariant : rootState.variants().values()) {
                addContexts(
                        rootSlot,
                        rootContexts,
                        enumerateSlot(
                                rootSlot,
                                rootSlot,
                                rootVariant,
                                builder,
                                new PreparationContext(),
                                new LinkedHashSet<>()));
            }
            if (rootContexts.isEmpty()) {
                throw new IllegalStateException("No valid compositions for root slot " + rootSlot);
            }
            contextsByRoot.put(rootSlot, List.copyOf(rootContexts));
            compositionsByRoot.put(rootSlot, rootContexts.size());
            runtimeCompositionCount += rootContexts.size();
            if (runtimeCompositionCount > MAX_COMPOSITIONS_PER_RUNTIME) {
                throw new IllegalArgumentException(
                        "Serving runtime exceeds the limit of " + MAX_COMPOSITIONS_PER_RUNTIME
                                + " complete root compositions (at least " + runtimeCompositionCount
                                + " observed); roots=" + compositionsByRoot);
            }
        }

        for (String rootSlot : rootSlots) {
            for (PreparationContext context : contextsByRoot.get(rootSlot)) {
                try (context) {
                    prepareSelectedSlot(
                            rootSlot,
                            builder,
                            context,
                            new LinkedHashSet<>(),
                            sharedArtifacts,
                            executionContext);
                    Map<String, SlotAssignment> assignments = slotAssignments(context);
                    AlgorithmRepository.CachedAlgorithmGraph preparedRoot =
                            context.takePrepared(rootSlot);
                    try {
                        builder.putRoot(
                                rootSlot,
                                ServingCompositionKey.from(context.selectedVariants()),
                                new PreparedComposition(
                                        preparedRoot,
                                        assignments));
                    } catch (RuntimeException | Error failure) {
                        Closeables.closeAfterFailure(failure, preparedRoot);
                        throw failure;
                    }
                }
            }
        }
        return builder.buildSnapshot(rootSlots);
    }

    private static Map<String, SlotAssignment> slotAssignments(PreparationContext context) {
        TreeMap<String, SlotAssignment> assignments = new TreeMap<>();
        context.selectedVariants().forEach((slot, variant) -> assignments.put(
                slot,
                new SlotAssignment(
                        String.valueOf(variant.variantId()),
                        Objects.requireNonNull(context.preparedSlots().get(slot)).runtimeId().algorithm())));
        return assignments;
    }

    private ServingSnapshot.SlotState loadSlot(
            String slotName,
            Preparation builder,
            LinkedHashSet<String> loadingSlots) throws Exception {
        if (loadingSlots.contains(slotName)) {
            throw new IllegalArgumentException(
                    "Cyclic EMS slot dependency: " + String.join(" -> ", loadingSlots) + " -> " + slotName);
        }
        ServingSnapshot.SlotState existing = builder.slots.get(slotName);
        if (existing != null) {
            return existing;
        }
        loadingSlots.add(slotName);
        try {
            Slot slot = stateSource.getDefaultVariantAndActiveExperiments(slotName);
            ServingSnapshot.SlotState loaded = inspectSlot(slot, rootSlots.contains(slotName), builder);
            builder.slots.put(slotName, loaded);
            Set<String> dependencies = new TreeSet<>();
            loaded.variants().values().forEach(variant -> dependencies.addAll(loaded.requiredSlots(variant)));
            for (String dependencySlot : dependencies) {
                loadSlot(dependencySlot, builder, loadingSlots);
            }
            return loaded;
        } finally {
            loadingSlots.remove(slotName);
        }
    }

    private ServingSnapshot.SlotState inspectSlot(
            Slot slot,
            boolean routedRoot,
            Preparation builder) {
        Objects.requireNonNull(slot, "EMS slot payload must not be null");
        VariantAssigner variantAssigner = new VariantAssigner(slot);
        Map<AlgorithmMetadata, Set<String>> requiredSlots = new HashMap<>();
        for (Variant variant : variantAssigner.variants().values()) {
            AlgorithmMetadata metadata = variant.algorithm();
            if (!requiredSlots.containsKey(metadata)) {
                AlgorithmRepository.AlgorithmArtifactInspection inspection = builder.inspect(
                        algorithmRepository,
                        metadata);
                AlgorithmDefinition definition = inspection.definition();
                EmsAlgorithmDefinitionValidator.validateDefinition(definition);
                requiredSlots.put(metadata, inspection.reachableSlotNames());
            }
        }
        return new ServingSnapshot.SlotState(
                variantAssigner,
                requiredSlots);
    }

    private List<PreparationContext> enumerateSlot(
            String rootSlotName,
            String slotName,
            Variant selected,
            Preparation builder,
            PreparationContext context,
            LinkedHashSet<String> resolvingSlots) throws Exception {
        if (resolvingSlots.contains(slotName)) {
            throw new IllegalArgumentException(
                    "Cyclic EMS slot dependency: " + String.join(" -> ", resolvingSlots) + " -> " + slotName);
        }
        Variant existing = context.selectedVariants().get(slotName);
        if (existing != null) {
            return existing.variantId() == selected.variantId() ? List.of(context) : List.of();
        }
        resolvingSlots.add(slotName);
        try {
            ServingSnapshot.SlotState state = Objects.requireNonNull(
                    builder.slots.get(slotName),
                    "Missing loaded EMS slot " + slotName);
            context.selectedVariants().put(slotName, selected);
            List<PreparationContext> contexts = List.of(context);
            for (String dependencySlot : state.requiredSlots(selected)) {
                ServingSnapshot.SlotState dependencyState = Objects.requireNonNull(
                        builder.slots.get(dependencySlot),
                        "Missing loaded EMS dependency slot " + dependencySlot);
                List<PreparationContext> next = new ArrayList<>();
                for (PreparationContext partial : contexts) {
                    Variant selectedDependency = partial.selectedVariants().get(dependencySlot);
                    List<Variant> variants = selectedDependency == null
                            ? List.copyOf(dependencyState.variants().values())
                            : List.of(selectedDependency);
                    for (Variant dependencyVariant : variants) {
                        PreparationContext branch = partial.copy();
                        addContexts(
                                rootSlotName,
                                next,
                                enumerateSlot(
                                        rootSlotName,
                                        dependencySlot,
                                        dependencyVariant,
                                        builder,
                                        branch,
                                        new LinkedHashSet<>(resolvingSlots)));
                    }
                }
                contexts = next;
            }
            return contexts;
        } finally {
            resolvingSlots.remove(slotName);
        }
    }

    private static void addContexts(
            String rootSlotName,
            List<PreparationContext> target,
            List<PreparationContext> additions) {
        for (PreparationContext addition : additions) {
            target.add(addition);
            if (target.size() > MAX_COMPOSITIONS_PER_ROOT) {
                throw new IllegalArgumentException(
                        "Serving root slot " + rootSlotName + " exceeds the limit of "
                                + MAX_COMPOSITIONS_PER_ROOT
                                + " complete compositions (at least " + target.size()
                                + " observed); contributing slots=" + contributingSlots(target));
            }
        }
    }

    private static Map<String, Integer> contributingSlots(List<PreparationContext> contexts) {
        TreeMap<String, Set<Integer>> variantsBySlot = new TreeMap<>();
        for (PreparationContext context : contexts) {
            context.selectedVariants().forEach((slot, variant) -> variantsBySlot
                    .computeIfAbsent(slot, ignored -> new LinkedHashSet<>())
                    .add(variant.variantId()));
        }
        TreeMap<String, Integer> contributing = new TreeMap<>();
        variantsBySlot.forEach((slot, variants) -> {
            if (variants.size() > 1) {
                contributing.put(slot, variants.size());
            }
        });
        return Collections.unmodifiableMap(contributing);
    }

    private AlgorithmRepository.CachedAlgorithmGraph prepareSelectedSlot(
            String slotName,
            Preparation builder,
            PreparationContext context,
            LinkedHashSet<String> resolvingSlots,
            AlgorithmRepository.SharedArtifactCatalog sharedArtifacts,
            ExecutionContext executionContext) {
        AlgorithmRepository.CachedAlgorithmGraph existing = context.preparedSlots().get(slotName);
        if (existing != null) {
            return existing;
        }
        if (!resolvingSlots.add(slotName)) {
            throw new IllegalArgumentException(
                    "Cyclic EMS slot dependency: " + String.join(" -> ", resolvingSlots) + " -> " + slotName);
        }
        try {
            Variant selected = Objects.requireNonNull(
                    context.selectedVariants().get(slotName),
                    "Missing selected EMS slot " + slotName);
            ServingSnapshot.SlotState state = Objects.requireNonNull(
                    builder.slots.get(slotName),
                    "Missing loaded EMS slot " + slotName);
            for (String dependencySlot : state.requiredSlots(selected)) {
                prepareSelectedSlot(
                        dependencySlot, builder, context, resolvingSlots, sharedArtifacts, executionContext);
            }
            AlgorithmRepository.CachedAlgorithmGraph prepared = buildSlotAlgorithm(
                    slotName,
                    selected,
                    builder,
                    context,
                    sharedArtifacts,
                    executionContext);
            context.putPrepared(slotName, prepared);
            return prepared;
        } finally {
            resolvingSlots.remove(slotName);
        }
    }

    private AlgorithmRepository.CachedAlgorithmGraph buildSlotAlgorithm(
            String slotName,
            Variant selected,
            Preparation builder,
            PreparationContext context,
            AlgorithmRepository.SharedArtifactCatalog sharedArtifacts,
            ExecutionContext executionContext) {
        ServingSnapshot.SlotState state = Objects.requireNonNull(builder.slots.get(slotName));
        AlgorithmMetadata metadata = selected.algorithm();
        TreeMap<String, AlgorithmRepository.CachedAlgorithmGraph> slotBindings = new TreeMap<>();
        for (String dependencySlot : state.requiredSlots(metadata)) {
            AlgorithmRepository.CachedAlgorithmGraph dependency = Objects.requireNonNull(
                    context.preparedSlots().get(dependencySlot),
                    "Slot " + dependencySlot + " was not prepared before " + metadata.algorithmId());
            slotBindings.put(dependencySlot, dependency);
        }
        AlgorithmRepository.CachedAlgorithmGraph graph = algorithmRepository.acquireComposedAlgorithm(
                builder.inspection(metadata),
                slotBindings,
                sharedArtifacts,
                executionContext);
        try {
            EmsAlgorithmDefinitionValidator.validateGraph(graph.runtimeId());
            if (rootSlots.contains(slotName)) {
                rootAlgorithmValidator.validate(slotName, graph.instance());
            }
            return graph;
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, graph);
            throw failure;
        }
    }

    static final class Preparation implements AutoCloseable {
        private final Map<String, ServingSnapshot.SlotState> slots = new LinkedHashMap<>();
        private final Map<String, Map<ServingCompositionKey, PreparedComposition>> roots =
                new LinkedHashMap<>();
        private final Map<AlgorithmMetadata, AlgorithmRepository.AlgorithmArtifactInspection> inspections =
                new LinkedHashMap<>();
        private boolean rootsTransferred;
        private boolean closed;

        private void putRoot(
                String rootSlot,
                ServingCompositionKey key,
                PreparedComposition composition) {
            Map<ServingCompositionKey, PreparedComposition> compositions =
                    roots.computeIfAbsent(rootSlot, ignored -> new LinkedHashMap<>());
            if (compositions.putIfAbsent(key, composition) != null) {
                throw new IllegalStateException(
                        "Root slot " + rootSlot + " prepared duplicate composition " + key.variantIds());
            }
        }

        private AlgorithmRepository.AlgorithmArtifactInspection inspect(
                AlgorithmRepository repository,
                AlgorithmMetadata metadata) {
            AlgorithmRepository.AlgorithmArtifactInspection existing = inspections.get(metadata);
            if (existing != null) {
                return existing;
            }
            AlgorithmRepository.AlgorithmArtifactInspection inspection = repository.inspectAlgorithm(metadata);
            inspections.put(metadata, inspection);
            return inspection;
        }

        private AlgorithmRepository.AlgorithmArtifactInspection inspection(AlgorithmMetadata metadata) {
            AlgorithmRepository.AlgorithmArtifactInspection inspection = inspections.get(metadata);
            if (inspection == null) {
                throw new IllegalStateException("Algorithm was not inspected before preparation: " + metadata);
            }
            return inspection;
        }

        List<AlgorithmDefinition> rootDefinitions(String rootSlot) {
            ServingSnapshot.SlotState root = Objects.requireNonNull(
                    slots.get(rootSlot),
                    "Missing inspected root slot " + rootSlot);
            return root.variants().values().stream()
                    .map(Variant::algorithm)
                    .map(this::inspection)
                    .map(AlgorithmRepository.AlgorithmArtifactInspection::definition)
                    .distinct()
                    .sorted(Comparator.comparing(definition -> definition.algorithmId().value()))
                    .toList();
        }

        private ServingSnapshot buildSnapshot(Set<String> rootSlots) {
            ServingSnapshot snapshot = new ServingSnapshot(
                    slots,
                    roots,
                    rootSlots);
            rootsTransferred = true;
            roots.clear();
            return snapshot;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            ArrayList<AutoCloseable> resources = new ArrayList<>();
            if (!rootsTransferred) {
                LinkedHashSet<AlgorithmRepository.CachedAlgorithmGraph> rootGraphs = new LinkedHashSet<>();
                roots.values().forEach(compositions -> compositions.values().forEach(
                        composition -> rootGraphs.add(composition.rootGraph())));
                resources.addAll(rootGraphs);
            }
            resources.addAll(inspections.values());
            Closeables.closeAll(
                    "Failed to close serving snapshot preparation",
                    resources.toArray(AutoCloseable[]::new));
        }
    }

    @FunctionalInterface
    interface RootAlgorithmValidator {
        void validate(String slotName, AlgorithmInstance<?> algorithmInstance);
    }

    private static final class PreparationContext implements AutoCloseable {
        private final Map<String, Variant> selectedVariants;
        private final Map<String, AlgorithmRepository.CachedAlgorithmGraph> preparedSlots;
        private boolean closed;

        private PreparationContext() {
            this(new LinkedHashMap<>());
        }

        private PreparationContext(Map<String, Variant> selectedVariants) {
            this.selectedVariants = selectedVariants;
            this.preparedSlots = new LinkedHashMap<>();
        }

        private PreparationContext copy() {
            return new PreparationContext(new LinkedHashMap<>(selectedVariants));
        }

        private Map<String, Variant> selectedVariants() {
            return selectedVariants;
        }

        private Map<String, AlgorithmRepository.CachedAlgorithmGraph> preparedSlots() {
            return preparedSlots;
        }

        private void putPrepared(
                String slot,
                AlgorithmRepository.CachedAlgorithmGraph prepared) {
            if (preparedSlots.putIfAbsent(slot, prepared) != null) {
                throw new IllegalStateException("Slot " + slot + " was prepared more than once");
            }
        }

        private AlgorithmRepository.CachedAlgorithmGraph takePrepared(String slot) {
            AlgorithmRepository.CachedAlgorithmGraph prepared = preparedSlots.remove(slot);
            if (prepared == null) {
                throw new IllegalStateException("Slot " + slot + " was not prepared");
            }
            return prepared;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            LinkedHashSet<AlgorithmRepository.CachedAlgorithmGraph> graphs =
                    new LinkedHashSet<>(preparedSlots.values());
            preparedSlots.clear();
            Closeables.closeAll(
                    "Failed to close serving composition preparation",
                    graphs.toArray(AutoCloseable[]::new));
        }
    }
}
