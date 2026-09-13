package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Declaration-only result of planning one reachable algorithm graph. */
public record AlgorithmGraphInspection(
        AlgorithmDefinition rootDefinition,
        Set<String> reachableSlotNames,
        Map<AlgorithmId, AlgorithmDefinition> packagedDefinitions,
        Set<AlgorithmId> sharedDependencies) {

    public AlgorithmGraphInspection {
        rootDefinition = Objects.requireNonNull(rootDefinition, "rootDefinition must not be null");
        reachableSlotNames = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(
                reachableSlotNames,
                "reachableSlotNames must not be null")));
        packagedDefinitions = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                packagedDefinitions,
                "packagedDefinitions must not be null")));
        sharedDependencies = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(
                sharedDependencies,
                "sharedDependencies must not be null")));
    }
}
