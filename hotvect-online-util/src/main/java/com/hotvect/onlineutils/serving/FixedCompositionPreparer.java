package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.util.Closeables;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Prepares the single recursive graph described by one fixed composition. */
final class FixedCompositionPreparer implements AutoCloseable {
    private final FixedAlgorithmComposition composition;
    private final AlgorithmRepository repository;
    private final Map<AlgorithmId, AlgorithmRepository.AlgorithmArtifactInspection> inspections =
            new LinkedHashMap<>();
    private final Map<AlgorithmId, AlgorithmRepository.CachedAlgorithmGraph> preparedAlgorithms =
            new LinkedHashMap<>();
    private final Map<String, AlgorithmRepository.CachedAlgorithmGraph> preparedSlots =
            new LinkedHashMap<>();
    private final Set<String> usedSlots = new LinkedHashSet<>();
    private boolean closed;

    FixedCompositionPreparer(
            FixedAlgorithmComposition composition,
            AlgorithmRepository repository) {
        this.composition = Objects.requireNonNull(composition, "composition must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    AlgorithmDefinition inspect() {
        try {
            discoverAlgorithm(composition.root(), new LinkedHashSet<>(), new LinkedHashSet<>());
            requireNoUnusedConfiguration();
            return Objects.requireNonNull(
                    inspections.get(composition.root()),
                    "Composition root was not inspected: " + composition.root()).definition();
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, this);
            throw failure;
        }
    }

    AlgorithmRepository.CachedAlgorithmGraph prepareInspected(ExecutionContext executionContext) {
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        AlgorithmRepository.CachedAlgorithmGraph root = null;
        try (AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                inspections.values())) {
            root = prepareAlgorithm(
                    composition.root(),
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>(),
                    sharedArtifacts,
                    executionContext);
            if (!preparedAlgorithms.remove(composition.root(), root)) {
                throw new IllegalStateException("Prepared fixed root was not retained by its composition");
            }
            close();
            return root;
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, root, this);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        LinkedHashSet<AlgorithmRepository.CachedAlgorithmGraph> graphs =
                new LinkedHashSet<>(preparedAlgorithms.values());
        preparedAlgorithms.clear();
        preparedSlots.clear();
        ArrayList<AutoCloseable> resources = new ArrayList<>(graphs);
        resources.addAll(inspections.values());
        inspections.clear();
        Closeables.closeAll(
                "Failed to close fixed composition preparation",
                resources.toArray(AutoCloseable[]::new));
    }

    private void discoverAlgorithm(
            AlgorithmId algorithmId,
            LinkedHashSet<AlgorithmId> resolvingAlgorithms,
            LinkedHashSet<String> resolvingSlots) {
        if (resolvingAlgorithms.contains(algorithmId)) {
            throw algorithmCycle(resolvingAlgorithms, algorithmId);
        }
        if (inspections.containsKey(algorithmId)) {
            return;
        }
        resolvingAlgorithms.add(algorithmId);
        try {
            AlgorithmMetadata metadata = composition.algorithm(algorithmId);
            AlgorithmRepository.AlgorithmArtifactInspection inspection = repository.inspectAlgorithm(metadata);
            inspections.put(algorithmId, inspection);
            for (String slotName : new TreeSet<>(inspection.reachableSlotNames())) {
                discoverSlot(slotName, resolvingAlgorithms, resolvingSlots);
            }
        } finally {
            resolvingAlgorithms.remove(algorithmId);
        }
    }

    private void discoverSlot(
            String slotName,
            LinkedHashSet<AlgorithmId> resolvingAlgorithms,
            LinkedHashSet<String> resolvingSlots) {
        if (resolvingSlots.contains(slotName)) {
            throw slotCycle(resolvingSlots, slotName);
        }
        if (!usedSlots.add(slotName)) {
            return;
        }
        resolvingSlots.add(slotName);
        try {
            discoverAlgorithm(composition.slotBinding(slotName), resolvingAlgorithms, resolvingSlots);
        } finally {
            resolvingSlots.remove(slotName);
        }
    }

    private AlgorithmRepository.CachedAlgorithmGraph prepareAlgorithm(
            AlgorithmId algorithmId,
            LinkedHashSet<AlgorithmId> resolvingAlgorithms,
            LinkedHashSet<String> resolvingSlots,
            AlgorithmRepository.SharedArtifactCatalog sharedArtifacts,
            ExecutionContext executionContext) {
        AlgorithmRepository.CachedAlgorithmGraph existing = preparedAlgorithms.get(algorithmId);
        if (existing != null) {
            return existing;
        }
        if (!resolvingAlgorithms.add(algorithmId)) {
            throw algorithmCycle(resolvingAlgorithms, algorithmId);
        }
        try {
            AlgorithmRepository.AlgorithmArtifactInspection inspection = Objects.requireNonNull(
                    inspections.get(algorithmId),
                    "Algorithm was not inspected before preparation: " + algorithmId);
            TreeMap<String, AlgorithmRepository.CachedAlgorithmGraph> slotBindings = new TreeMap<>();
            for (String slotName : new TreeSet<>(inspection.reachableSlotNames())) {
                slotBindings.put(
                        slotName,
                        prepareSlot(
                                slotName,
                                resolvingAlgorithms,
                                resolvingSlots,
                                sharedArtifacts,
                                executionContext));
            }
            AlgorithmRepository.CachedAlgorithmGraph prepared = repository.acquireComposedAlgorithm(
                    inspection,
                    slotBindings,
                    sharedArtifacts,
                    executionContext);
            preparedAlgorithms.put(algorithmId, prepared);
            return prepared;
        } finally {
            resolvingAlgorithms.remove(algorithmId);
        }
    }

    private AlgorithmRepository.CachedAlgorithmGraph prepareSlot(
            String slotName,
            LinkedHashSet<AlgorithmId> resolvingAlgorithms,
            LinkedHashSet<String> resolvingSlots,
            AlgorithmRepository.SharedArtifactCatalog sharedArtifacts,
            ExecutionContext executionContext) {
        AlgorithmRepository.CachedAlgorithmGraph existing = preparedSlots.get(slotName);
        if (existing != null) {
            return existing;
        }
        if (!resolvingSlots.add(slotName)) {
            throw slotCycle(resolvingSlots, slotName);
        }
        try {
            AlgorithmRepository.CachedAlgorithmGraph prepared = prepareAlgorithm(
                    composition.slotBinding(slotName),
                    resolvingAlgorithms,
                    resolvingSlots,
                    sharedArtifacts,
                    executionContext);
            preparedSlots.put(slotName, prepared);
            return prepared;
        } finally {
            resolvingSlots.remove(slotName);
        }
    }

    private void requireNoUnusedConfiguration() {
        Set<AlgorithmId> unusedAlgorithms = new LinkedHashSet<>(composition.algorithmIds());
        unusedAlgorithms.removeAll(inspections.keySet());
        if (!unusedAlgorithms.isEmpty()) {
            throw new IllegalArgumentException(
                    "Composition algorithms are not reachable from root " + composition.root()
                            + ": " + unusedAlgorithms.stream()
                                    .sorted(Comparator.comparing(AlgorithmId::algorithmName)
                                            .thenComparing(AlgorithmId::algorithmVersion))
                                    .toList());
        }

        TreeSet<String> unusedSlots = new TreeSet<>(composition.slotNames());
        unusedSlots.removeAll(usedSlots);
        if (!unusedSlots.isEmpty()) {
            throw new IllegalArgumentException(
                    "Composition slot bindings are not reachable from root " + composition.root()
                            + ": " + unusedSlots);
        }
    }

    private static IllegalArgumentException algorithmCycle(
            LinkedHashSet<AlgorithmId> resolving,
            AlgorithmId repeated) {
        ArrayList<String> path = new ArrayList<>();
        resolving.forEach(algorithmId -> path.add(algorithmId.value()));
        path.add(repeated.value());
        return new IllegalArgumentException("Cyclic fixed composition algorithm dependency: " + String.join(" -> ", path));
    }

    private static IllegalArgumentException slotCycle(
            LinkedHashSet<String> resolving,
            String repeated) {
        List<String> path = new ArrayList<>(resolving);
        path.add(repeated);
        return new IllegalArgumentException("Cyclic fixed composition slot dependency: " + String.join(" -> ", path));
    }
}
