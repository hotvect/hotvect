package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServingContractValidationTest {
    private final Ranker<TestShared, TestAction> ranker =
            request -> RankingResponse.newResponse(List.of());

    @Test
    void acceptsTheExactAlgorithmContract() {
        assertDoesNotThrow(() -> slot(new TypeToken<Ranker<TestShared, TestAction>>() {})
                .validate(AlgorithmInstance.externalAlgorithm(
                        "test-ranker",
                        new TypeToken<Ranker<TestShared, TestAction>>() {},
                        ranker)));
    }

    @Test
    void acceptsAnImplementationSubtypeOfTheSlotContract() {
        assertDoesNotThrow(() -> slot(new TypeToken<Ranker<TestShared, TestAction>>() {})
                .validate(AlgorithmInstance.externalAlgorithm(
                        "test-ranker",
                        TestRanker.class,
                        new TestRanker())));
    }

    @Test
    void rejectsAMismatchedSharedType() {
        ServingSlot slot = slot(new TypeToken<Ranker<String, TestAction>>() {});
        AlgorithmInstance<Ranker<TestShared, TestAction>> candidate = AlgorithmInstance.externalAlgorithm(
                "test-ranker",
                new TypeToken<Ranker<TestShared, TestAction>>() {},
                ranker);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> slot.validate(candidate));

        assertEquals(
                "Algorithm test-ranker@NA is incompatible with serving slot root-slot: expected "
                        + Ranker.class.getName() + "<java.lang.String, " + TestAction.class.getName() + ">"
                        + " but artifact declares "
                        + Ranker.class.getName() + "<" + TestShared.class.getName() + ", "
                        + TestAction.class.getName() + ">",
                error.getMessage());
    }

    @Test
    void rejectsADifferentAlgorithmInterface() {
        TopK<TestShared, TestAction> topK = request -> null;
        AlgorithmInstance<TopK<TestShared, TestAction>> candidate = AlgorithmInstance.externalAlgorithm(
                "test-topk",
                new TypeToken<TopK<TestShared, TestAction>>() {},
                topK);

        assertThrows(
                IllegalArgumentException.class,
                () -> slot(new TypeToken<Ranker<TestShared, TestAction>>() {}).validate(candidate));
    }

    @Test
    void rejectsMismatchedDeeplyNestedGenericContracts() {
        AlgorithmInstance<ThemedTopK<TestShared, NestedAction<List<String>>>> candidate =
                AlgorithmInstance.externalAlgorithm(
                        "test-themed-topk",
                        new TypeToken<ThemedTopK<TestShared, NestedAction<List<String>>>>() {},
                        request -> null);

        assertThrows(
                IllegalArgumentException.class,
                () -> slot(new TypeToken<ThemedTopK<TestShared, NestedAction<List<Integer>>>>() {})
                        .validate(candidate));
    }

    private static ServingSlot slot(TypeToken<? extends Algorithm> algorithmType) {
        return ServingSlot.builder("root-slot", algorithmType)
                .touchpoints(Set.of("test-touchpoint"))
                .build();
    }

    public record TestShared(String value) {
    }

    public record TestAction(String value) {
    }

    public record NestedAction<T>(T value) {
    }

    private static final class TestRanker implements Ranker<TestShared, TestAction> {
        @Override
        public RankingResponse<TestAction> rank(RankingRequest<TestShared, TestAction> request) {
            return RankingResponse.newResponse(List.of());
        }
    }
}
