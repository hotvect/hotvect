package com.hotvect.onlineutils.serving;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmTypeContract;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** The complete algorithm invocation type accepted by one EMS slot. */
public final class ServingSlot {
    private static final Set<Class<? extends Algorithm>> SUPPORTED_ALGORITHM_TYPES =
            Set.of(Ranker.class, TopK.class, ThemedTopK.class);
    private static final Pattern SLOT_NAME = Pattern.compile("^[a-z0-9-]+$");

    private final String name;
    private final TypeToken<? extends Algorithm> algorithmType;
    private final Set<String> touchpoints;

    private ServingSlot(
            String name,
            TypeToken<? extends Algorithm> algorithmType,
            Set<String> touchpoints) {
        this.name = requireSlotName(name);
        this.algorithmType = requireSupportedAlgorithmType(algorithmType);
        Set<String> declaredTouchpoints = Objects.requireNonNull(touchpoints, "touchpoints must not be null");
        if (declaredTouchpoints.isEmpty()) {
            throw new IllegalArgumentException("touchpoints must not be empty");
        }
        for (String touchpoint : declaredTouchpoints) {
            requireNonBlank(touchpoint, "touchpoint");
        }
        this.touchpoints = Set.copyOf(declaredTouchpoints);
    }

    /**
     * Starts an immutable serving-slot declaration.
     *
     * <p>The name must use the EMS slot grammar {@code [a-z0-9-]+}.
     */
    public static <ALGORITHM extends Algorithm> Builder builder(
            String name,
            TypeToken<ALGORITHM> algorithmType) {
        return new Builder(name, algorithmType);
    }

    public String name() {
        return name;
    }

    public TypeToken<? extends Algorithm> algorithmType() {
        return algorithmType;
    }

    public Set<String> touchpoints() {
        return touchpoints;
    }

    void validate(AlgorithmInstance<?> algorithmInstance) {
        AlgorithmInstance<?> candidate = Objects.requireNonNull(
                algorithmInstance,
                "algorithmInstance must not be null");
        if (!algorithmType.isSupertypeOf(candidate.algorithmType())) {
            throw new IllegalArgumentException(
                    "Algorithm " + candidate.algorithmDefinition().algorithmId().value()
                            + " is incompatible with serving slot " + name
                            + ": expected " + algorithmType
                            + " but artifact declares " + candidate.algorithmType());
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ServingSlot that
                && name.equals(that.name)
                && algorithmType.equals(that.algorithmType)
                && touchpoints.equals(that.touchpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, algorithmType, touchpoints);
    }

    @Override
    public String toString() {
        return "ServingSlot["
                + "name=" + name
                + ", algorithmType=" + algorithmType
                + ", touchpoints=" + touchpoints
                + ']';
    }

    /** Builder for one fully typed serving slot. */
    public static final class Builder {
        private final String name;
        private final TypeToken<? extends Algorithm> algorithmType;
        private Set<String> touchpoints;

        private Builder(String name, TypeToken<? extends Algorithm> algorithmType) {
            this.name = name;
            this.algorithmType = algorithmType;
        }

        /** Declares the non-empty set of application touchpoints routed to this slot. */
        public Builder touchpoints(Set<String> touchpoints) {
            this.touchpoints = Objects.requireNonNull(touchpoints, "touchpoints must not be null");
            return this;
        }

        /** Creates the immutable slot after validating every contract field. */
        public ServingSlot build() {
            return new ServingSlot(
                    name,
                    Objects.requireNonNull(algorithmType, "algorithmType must not be null"),
                    Objects.requireNonNull(touchpoints, "touchpoints are required"));
        }
    }

    private static TypeToken<? extends Algorithm> requireSupportedAlgorithmType(
            TypeToken<? extends Algorithm> algorithmType) {
        TypeToken<? extends Algorithm> resolved = AlgorithmTypeContract.requireFullyResolved(algorithmType);
        if (!SUPPORTED_ALGORITHM_TYPES.contains(resolved.getRawType())) {
            throw new IllegalArgumentException(
                    "Unsupported serving algorithm interface: " + resolved.getRawType().getName());
        }
        return resolved;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireSlotName(String value) {
        String slotName = requireNonBlank(value, "name");
        if (!SLOT_NAME.matcher(slotName).matches()) {
            throw new IllegalArgumentException("name must match " + SLOT_NAME.pattern());
        }
        return slotName;
    }
}
