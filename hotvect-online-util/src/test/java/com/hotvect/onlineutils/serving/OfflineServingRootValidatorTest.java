package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OfflineServingRootValidatorTest {

    @Test
    void acceptsSupportedFullyResolvedRootContracts() {
        Ranker<String, String> ranker = request -> RankingResponse.newResponse(List.of());
        BulkScorer<String, String> bulkScorer = new BulkScorer<>() {};
        TopK<String, String> topK = request -> TopKResponse.newResponse(List.of());
        ThemedTopK<String, String> themedTopK = request ->
                ThemedTopKResponse.newResponse("theme", List.of(), Map.of());

        assertDoesNotThrow(() -> OfflineServingRootValidator.validate(
                "root", instance("ranker", ranker, new TypeToken<Ranker<String, String>>() {})));
        assertDoesNotThrow(() -> OfflineServingRootValidator.validate(
                "root", instance("bulk-scorer", bulkScorer, new TypeToken<BulkScorer<String, String>>() {})));
        assertDoesNotThrow(() -> OfflineServingRootValidator.validate(
                "root", instance("top-k", topK, new TypeToken<TopK<String, String>>() {})));
        assertDoesNotThrow(() -> OfflineServingRootValidator.validate(
                "root", instance("themed-top-k", themedTopK, new TypeToken<ThemedTopK<String, String>>() {})));
    }

    @Test
    void acceptsAlternativeVariantsWithTheSameInvocationContract() {
        Ranker<String, String> first = request -> RankingResponse.newResponse(List.of());
        Ranker<String, String> second = request -> RankingResponse.newResponse(List.of());

        assertDoesNotThrow(() -> OfflineServingRootValidator.validateCompatible(
                "root",
                List.of(
                        instance("first", first, new TypeToken<Ranker<String, String>>() {}),
                        instance("second", second, new TypeToken<Ranker<String, String>>() {}))));
    }

    @Test
    void rejectsVariantsWithDifferentInvocationInterfaces() {
        Ranker<String, String> ranker = request -> RankingResponse.newResponse(List.of());
        TopK<String, String> topK = request -> TopKResponse.newResponse(List.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OfflineServingRootValidator.validateCompatible(
                        "root",
                        List.of(
                                instance("ranker", ranker, new TypeToken<Ranker<String, String>>() {}),
                                instance("top-k", topK, new TypeToken<TopK<String, String>>() {}))));

        assertTrue(failure.getMessage().contains("variants must share one invocation contract"));
    }

    @Test
    void rejectsVariantsWithDifferentDomainTypes() {
        Ranker<String, String> strings = request -> RankingResponse.newResponse(List.of());
        Ranker<Integer, String> integers = request -> RankingResponse.newResponse(List.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OfflineServingRootValidator.validateCompatible(
                        "root",
                        List.of(
                                instance("strings", strings, new TypeToken<Ranker<String, String>>() {}),
                                instance("integers", integers, new TypeToken<Ranker<Integer, String>>() {}))));

        assertTrue(failure.getMessage().contains("variants must share one invocation contract"));
    }

    @Test
    void rejectsUnresolvedRootDomainTypes() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OfflineServingRootValidator.validate("root", unresolvedRanker("generic")));

        assertTrue(failure.getMessage().contains("must be fully resolved"));
    }

    @Test
    void rejectsUnsupportedAlgorithmRoots() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OfflineServingRootValidator.validate(
                        "root",
                        instance(
                                "unsupported",
                                new UnsupportedAlgorithm(),
                                TypeToken.of(UnsupportedAlgorithm.class))));

        assertTrue(failure.getMessage().contains("must implement Ranker, BulkScorer, TopK, or ThemedTopK"));
    }

    private static <ALGORITHM extends Algorithm> AlgorithmInstance<ALGORITHM> instance(
            String name,
            ALGORITHM algorithm,
            TypeToken<ALGORITHM> algorithmType) {
        return new AlgorithmInstance<>(definition(name), null, algorithm, algorithmType);
    }

    private static <SHARED, ACTION> AlgorithmInstance<Ranker<SHARED, ACTION>> unresolvedRanker(
            String name) {
        Ranker<SHARED, ACTION> ranker = request -> RankingResponse.newResponse(List.of());
        return instance(name, ranker, new TypeToken<Ranker<SHARED, ACTION>>() {});
    }

    private static AlgorithmDefinition definition(String name) {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                new AlgorithmId(name, "1"),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static final class UnsupportedAlgorithm implements Algorithm {
    }
}
