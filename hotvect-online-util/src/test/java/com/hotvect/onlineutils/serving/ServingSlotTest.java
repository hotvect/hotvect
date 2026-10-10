package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.algorithms.State;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ServingSlotTest {
    @ParameterizedTest
    @MethodSource("supportedAlgorithmTypes")
    void acceptsServingAlgorithmTypes(TypeToken<? extends Algorithm> algorithmType) {
        assertDoesNotThrow(() -> slot(algorithmType));
    }

    @Test
    void retainsTheCompleteAlgorithmContract() {
        TypeToken<Ranker<String, List<String>>> algorithmType = new TypeToken<>() {};

        ServingSlot slot = ServingSlot.builder("slot", algorithmType)
                .touchpoints(Set.of("search"))
                .build();

        assertEquals(algorithmType, slot.algorithmType());
    }

    @Test
    void rejectsRawGenericAlgorithmTypes() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ServingSlot.builder("slot", TypeToken.of(Ranker.class))
                        .touchpoints(Set.of("search"))
                        .build());

        assertEquals(
                "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "
                        + Ranker.class.getName(),
                error.getMessage());
    }

    @Test
    void rejectsWildcardAndTypeVariableAlgorithmTypes() {
        IllegalArgumentException wildcard = assertThrows(
                IllegalArgumentException.class,
                () -> ServingSlot.builder("slot", new TypeToken<Ranker<String, ?>>() {})
                        .touchpoints(Set.of("search"))
                        .build());
        IllegalArgumentException typeVariable = assertThrows(
                IllegalArgumentException.class,
                () -> ServingSlot.builder("slot", typeVariableType())
                        .touchpoints(Set.of("search"))
                        .build());

        assertEquals(
                "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "
                        + Ranker.class.getName() + "<java.lang.String, ?>",
                wildcard.getMessage());
        assertTrue(typeVariable.getMessage().startsWith(
                "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "
                        + Ranker.class.getName() + "<java.lang.String, "));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void requiresAConcreteOwnerForNonStaticMemberTypes() {
        TypeToken<Ranker<GenericOuter<String>.InnerPayload, String>> concreteType = new TypeToken<>() {};

        assertDoesNotThrow(() -> slot(concreteType));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> slot(new TypeToken<Ranker<GenericOuter.InnerPayload, String>>() {}));

        assertTrue(error.getMessage().startsWith(
                "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "));
    }

    @Test
    void acceptsStaticNestedGenericTypes() {
        assertDoesNotThrow(() -> slot(
                new TypeToken<Ranker<Map.Entry<String, String>, List<String>>>() {}));
    }

    @Test
    @SuppressWarnings("removal")
    void rejectsState() {
        assertUnsupported(TypeToken.of(State.class));
    }

    @Test
    void rejectsScorer() {
        assertUnsupported(new TypeToken<Scorer<String>>() {});
    }

    @Test
    void rejectsBulkScorer() {
        assertUnsupported(new TypeToken<BulkScorer<String, String>>() {});
    }

    @Test
    void hasValueSemantics() {
        ServingSlot first = slot(new TypeToken<Ranker<String, String>>() {});
        ServingSlot equivalent = ServingSlot.builder(
                        "slot",
                        new TypeToken<Ranker<String, String>>() {})
                .touchpoints(Set.of("search"))
                .build();
        ServingSlot different = ServingSlot.builder(
                        "other-slot",
                        new TypeToken<Ranker<String, String>>() {})
                .touchpoints(Set.of("search"))
                .build();

        assertEquals(first, equivalent);
        assertEquals(first.hashCode(), equivalent.hashCode());
        assertTrue(first.toString().contains("name=slot"));
        assertTrue(first.toString().contains("algorithmType=" + Ranker.class.getName()));
        assertTrue(first.toString().contains("touchpoints=[search]"));
        org.junit.jupiter.api.Assertions.assertNotEquals(first, different);
    }

    @Test
    void requiresRootSlotNamesToUseTheSlotGrammar() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ServingSlot.builder(
                                "Candidate_Slot",
                                new TypeToken<Ranker<String, String>>() {})
                        .touchpoints(Set.of("search"))
                        .build());

        assertEquals("name must match ^[a-z0-9-]+$", error.getMessage());
    }

    @Test
    void requiresAtLeastOneTouchpoint() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ServingSlot.builder(
                                "slot",
                                new TypeToken<Ranker<String, String>>() {})
                        .touchpoints(Set.of())
                        .build());

        assertEquals("touchpoints must not be empty", error.getMessage());
    }

    private static Stream<TypeToken<? extends Algorithm>> supportedAlgorithmTypes() {
        return Stream.of(
                new TypeToken<Ranker<String, String>>() {},
                new TypeToken<TopK<String, String>>() {},
                new TypeToken<ThemedTopK<String, String>>() {});
    }

    private static ServingSlot slot(TypeToken<? extends Algorithm> algorithmType) {
        return ServingSlot.builder("slot", algorithmType)
                .touchpoints(Set.of("search"))
                .build();
    }

    private static <T> TypeToken<Ranker<String, T>> typeVariableType() {
        return new TypeToken<>() {};
    }

    private static final class GenericOuter<OWNER> {
        private final class InnerPayload {
        }
    }

    private static void assertUnsupported(TypeToken<? extends Algorithm> algorithmType) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> slot(algorithmType));

        assertEquals(
                "Unsupported serving algorithm interface: " + algorithmType.getRawType().getName(),
                error.getMessage());
    }
}
