package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.experimentmanagement.variantassignment.VariantAssigner;
import com.hotvect.onlineutils.util.Closeables;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One immutable, completely prepared serving generation. */
final class ServingSnapshot implements AutoCloseable {
    private final Map<String, SlotState> slots;
    private final Map<String, Map<ServingCompositionKey, PreparedComposition>> roots;
    private final Map<String, ServingSlotAssignment> assignments;
    private final SnapshotResources resources;
    private final long generation;
    private final Instant activatedAt;
    private final Duration preparationDuration;

    ServingSnapshot(
            Map<String, SlotState> slots,
            Map<String, Map<ServingCompositionKey, PreparedComposition>> roots,
            Set<String> configuredRootSlots) {
        this.slots = immutableSlots(slots);
        this.roots = immutableRoots(roots);
        this.assignments = assignments(this.slots, configuredRootSlots);
        this.resources = new SnapshotResources(this.roots);
        this.generation = 0;
        this.activatedAt = null;
        this.preparationDuration = null;
    }

    private ServingSnapshot(
            ServingSnapshot prepared,
            long generation,
            Instant activatedAt,
            Duration preparationDuration) {
        Objects.requireNonNull(prepared, "prepared must not be null");
        this.slots = prepared.slots;
        this.roots = prepared.roots;
        this.assignments = prepared.assignments;
        this.resources = prepared.resources;
        this.generation = generation;
        this.activatedAt = activatedAt;
        this.preparationDuration = preparationDuration;
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (generation == 0 && (activatedAt != null || preparationDuration != null)) {
            throw new IllegalArgumentException("Unactivated snapshot must not have activation metadata");
        }
        if (generation > 0 && (activatedAt == null || preparationDuration == null)) {
            throw new IllegalArgumentException("Activated snapshot must have activation metadata");
        }
    }

    ServingSnapshot activate(long generation, Instant activatedAt, Duration preparationDuration) {
        return new ServingSnapshot(this, generation, activatedAt, preparationDuration);
    }

    @Override
    public void close() {
        resources.close();
    }

    long generation() {
        return generation;
    }

    Instant activatedAt() {
        return activatedAt;
    }

    Duration preparationDuration() {
        return preparationDuration;
    }

    Map<ServingCompositionKey, PreparedComposition> roots(String rootSlot) {
        Map<ServingCompositionKey, PreparedComposition> compositions =
                roots.get(rootSlot);
        if (compositions == null) {
            throw new IllegalStateException("Missing prepared root slot " + rootSlot);
        }
        return compositions;
    }

    Variant assign(String slotName, String assignmentKey) {
        return slotState(slotName).assign(assignmentKey);
    }

    Set<String> requiredSlots(String slotName, Variant variant) {
        return slotState(slotName).requiredSlots(variant);
    }

    Map<String, ServingSlotAssignment> assignments() {
        return assignments;
    }

    List<AlgorithmDefinition> rootDefinitions(String rootSlot) {
        return roots(rootSlot).values().stream()
                .map(PreparedComposition::rootGraph)
                .map(graph -> graph.instance().algorithmDefinition())
                .distinct()
                .sorted(java.util.Comparator.comparing(definition -> definition.algorithmId().value()))
                .toList();
    }

    List<AlgorithmInstance<?>> rootInstances(String rootSlot) {
        return roots(rootSlot).values().stream()
                .map(PreparedComposition::rootGraph)
                .map(AlgorithmRepository.CachedAlgorithmGraph::instance)
                .toList();
    }

    PreparedComposition select(String rootSlot, String assignmentKey) {
        TreeMap<String, Variant> selected = new TreeMap<>();
        selectVariant(rootSlot, assignmentKey, selected, new LinkedHashSet<>());
        ServingCompositionKey key = ServingCompositionKey.from(selected);
        PreparedComposition composition = roots(rootSlot).get(key);
        if (composition == null) {
            throw new IllegalStateException(
                    "No prepared graph for root slot " + rootSlot
                            + " and selected variants " + key.variantIds());
        }
        return composition;
    }

    private void selectVariant(
            String slotName,
            String assignmentKey,
            Map<String, Variant> selected,
            LinkedHashSet<String> resolvingSlots) {
        if (resolvingSlots.contains(slotName)) {
            throw new IllegalArgumentException(
                    "Cyclic EMS slot dependency: " + String.join(" -> ", resolvingSlots) + " -> " + slotName);
        }
        if (selected.containsKey(slotName)) {
            return;
        }
        resolvingSlots.add(slotName);
        try {
            Variant selectedVariant = assign(slotName, assignmentKey);
            selected.put(slotName, selectedVariant);
            for (String dependencySlot : requiredSlots(slotName, selectedVariant)) {
                selectVariant(dependencySlot, assignmentKey, selected, resolvingSlots);
            }
        } finally {
            resolvingSlots.remove(slotName);
        }
    }

    private static Map<String, ServingSlotAssignment> assignments(
            Map<String, SlotState> slots,
            Set<String> configuredRootSlots) {
        TreeMap<String, ServingSlotAssignment> assignments = new TreeMap<>();
        slots.forEach((slotName, state) -> assignments.put(
                slotName,
                new ServingSlotAssignment(
                        configuredRootSlots.contains(slotName),
                        String.valueOf(state.defaultVariantId()),
                        state.variants().keySet().stream()
                                .map(String::valueOf)
                                .collect(java.util.stream.Collectors.toSet()))));
        return Collections.unmodifiableMap(assignments);
    }

    private SlotState slotState(String slotName) {
        SlotState state = slots.get(slotName);
        if (state == null) {
            throw new IllegalStateException("Missing active EMS slot " + slotName);
        }
        return state;
    }

    static final class SlotState {
        private final VariantAssigner variantAssigner;
        private final Map<AlgorithmMetadata, Set<String>> requiredSlotsByAlgorithm;

        SlotState(
                VariantAssigner variantAssigner,
                Map<AlgorithmMetadata, Set<String>> requiredSlotsByAlgorithm) {
            this.variantAssigner = Objects.requireNonNull(variantAssigner, "variantAssigner must not be null");
            this.requiredSlotsByAlgorithm = Map.copyOf(
                    Objects.requireNonNull(requiredSlotsByAlgorithm, "requiredSlotsByAlgorithm must not be null"));
        }

        int defaultVariantId() {
            return variantAssigner.defaultVariantId();
        }

        Map<Integer, Variant> variants() {
            return variantAssigner.variants();
        }

        Set<String> requiredSlots(Variant variant) {
            return requiredSlots(variant.algorithm());
        }

        Set<String> requiredSlots(AlgorithmMetadata metadata) {
            Set<String> slots = requiredSlotsByAlgorithm.get(metadata);
            if (slots == null) {
                throw new IllegalStateException("No inspected definition for " + metadata.algorithmId());
            }
            return slots;
        }

        Variant assign(String assignmentKey) {
            return variantAssigner.assign(assignmentKey);
        }
    }

    private static Map<String, SlotState> immutableSlots(Map<String, SlotState> slots) {
        return Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(slots, "slots must not be null")));
    }

    private static Map<String, Map<ServingCompositionKey, PreparedComposition>> immutableRoots(
            Map<String, Map<ServingCompositionKey, PreparedComposition>> roots) {
        TreeMap<String, Map<ServingCompositionKey, PreparedComposition>> copied = new TreeMap<>();
        Objects.requireNonNull(roots, "roots must not be null").forEach((rootSlot, compositions) ->
                copied.put(rootSlot, Map.copyOf(compositions)));
        return Collections.unmodifiableMap(copied);
    }

    private static final class SnapshotResources implements AutoCloseable {
        private final List<AlgorithmRepository.CachedAlgorithmGraph> rootGraphs;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SnapshotResources(
                Map<String, Map<ServingCompositionKey, PreparedComposition>> roots) {
            LinkedHashSet<AlgorithmRepository.CachedAlgorithmGraph> graphs = new LinkedHashSet<>();
            roots.values().forEach(compositions -> compositions.values().forEach(
                    composition -> graphs.add(composition.rootGraph())));
            this.rootGraphs = List.copyOf(graphs);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Closeables.closeAll(
                    "Failed to close serving snapshot",
                    rootGraphs.toArray(AutoCloseable[]::new));
        }
    }
}

record PreparedComposition(
        AlgorithmRepository.CachedAlgorithmGraph rootGraph,
        Map<String, SlotAssignment> assignments) {
    PreparedComposition {
        rootGraph = Objects.requireNonNull(rootGraph, "rootGraph must not be null");
        assignments = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(assignments, "assignments must not be null")));
    }
}

/** Exact EMS variant combination selecting one prepared root graph. */
record ServingCompositionKey(Map<String, Integer> variantIds) {
    static ServingCompositionKey from(Map<String, Variant> selectedVariants) {
        TreeMap<String, Integer> ids = new TreeMap<>();
        selectedVariants.forEach((slotName, variant) -> ids.put(slotName, variant.variantId()));
        return new ServingCompositionKey(ids);
    }

    ServingCompositionKey {
        variantIds = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(variantIds, "variantIds must not be null")));
    }
}
