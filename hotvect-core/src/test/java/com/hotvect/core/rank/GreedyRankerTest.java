package com.hotvect.core.rank;

import com.google.common.hash.Hashing;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.core.score.Estimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

class GreedyRankerTest {
    private final ToIntFunction<String> toMockFeatureFunction = s -> {
        if (s != null) {
            return Hashing.murmur3_32_fixed().hashUnencodedChars(s).asInt();
        } else {
            return 0;
        }
    };
    private final RankingVectorizer<String, String> mockVectorizer =
            new RankingVectorizer<String, String>() {
                @Override
                public List<SparseVector> apply(RankingRequest<String, String> rankingRequest) {
                    return rankingRequest.availableActions().stream().map(x -> new SparseVector(new int[]{toMockFeatureFunction.applyAsInt(x)}))
                            .collect(Collectors.toList());
                }

                @Override
                public SortedSet<? extends Namespace> getUsedFeatures() {
                    return null;
                }
            };


    private final Estimator mockEstimator = featureVector -> featureVector.getCategoricalIndices()[0] % 10;

    @ParameterizedTest
    @MethodSource("requests")
    void apply(RankingRequest<String, String> request) {

        GreedyRanker<String, String> greedyRanker = new GreedyRanker<>(mockVectorizer, mockEstimator);

        List<RankingDecision<String>> actual = greedyRanker.rank(request).decisions();

        actual_scores_are_in_reverse_natural_order(actual);
        skuId_to_score_mapping_is_correct(request, actual);


    }

    private void skuId_to_score_mapping_is_correct(
            RankingRequest<String, String> request,
            List<RankingDecision<String>> actual
    ) {
        actual.forEach(rd -> {
            int inputIndex = Integer.parseInt(rd.actionId().substring("action-".length()));
            String inputAction = request.availableActions().get(inputIndex);
            Assertions.assertEquals(inputAction, rd.action());
            Assertions.assertEquals(toMockFeatureFunction.applyAsInt(inputAction) % 10, rd.score(), 0.000001);
        });

    }

    private void actual_scores_are_in_reverse_natural_order(List<RankingDecision<String>> actual) {
        var actualScores = actual.stream().map(RankingDecision::score).collect(toList());
        // Note how the order should be reverse order
        var expectedScores = actual.stream().map(RankingDecision::score).sorted((d1, d2) -> Double.compare(d2, d1)).toList();
        Assertions.assertEquals(expectedScores, actualScores);

    }

    @Test
    void equalScoresAreTieBrokenByStableActionIdKey() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(
                        AvailableAction.of("sku-b", "b"),
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-c", "c")
                )
        );
        RankingVectorizer<String, String> vectorizer = new RankingVectorizer<>() {
            @Override
            public List<SparseVector> apply(RankingRequest<String, String> rankingRequest) {
                return rankingRequest.availableActions().stream()
                        .map(ignored -> new SparseVector(new int[]{1}))
                        .toList();
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return null;
            }
        };
        GreedyRanker<String, String> greedyRanker = new GreedyRanker<>(vectorizer, ignored -> 1.0);

        RankingResponse<String> response = greedyRanker.rank(request);

        List<String> expectedActionIds = request.actions().stream()
                .map(AvailableAction::actionId)
                .sorted(stableTieBreakOrder(request.exampleId()))
                .toList();
        Assertions.assertEquals(
                expectedActionIds,
                response.rankingDecisions().stream().map(RankingDecision::actionId).toList()
        );
    }

    @Test
    void propagatesAvailableActionAdditionalProperties() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(AvailableAction.of("sku-a", "a", Map.of("source", "request")))
        );
        RankingVectorizer<String, String> vectorizer = new RankingVectorizer<>() {
            @Override
            public List<SparseVector> apply(RankingRequest<String, String> rankingRequest) {
                return List.of(new SparseVector(new int[]{1}));
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return null;
            }
        };
        GreedyRanker<String, String> greedyRanker = new GreedyRanker<>(vectorizer, ignored -> 1.0);

        RankingResponse<String> response = greedyRanker.rank(request);

        Assertions.assertEquals(
                Map.of("source", "request"),
                response.rankingDecisions().get(0).additionalProperties()
        );
    }

    @Test
    void rejectsTooFewVectorizedActions() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-b", "b")
                )
        );
        RankingVectorizer<String, String> vectorizer = new RankingVectorizer<>() {
            @Override
            public List<SparseVector> apply(RankingRequest<String, String> rankingRequest) {
                return List.of(new SparseVector(new int[]{1}));
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return null;
            }
        };

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new GreedyRanker<>(vectorizer, ignored -> 1.0).rank(request)
        );
        Assertions.assertEquals("RankingVectorizer returned 1 vectors for 2 actions", error.getMessage());
    }

    @Test
    void rejectsTooManyVectorizedActions() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(AvailableAction.of("sku-a", "a"))
        );
        RankingVectorizer<String, String> vectorizer = new RankingVectorizer<>() {
            @Override
            public List<SparseVector> apply(RankingRequest<String, String> rankingRequest) {
                return List.of(new SparseVector(new int[]{1}), new SparseVector(new int[]{2}));
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return null;
            }
        };

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new GreedyRanker<>(vectorizer, ignored -> 1.0).rank(request)
        );
        Assertions.assertEquals("RankingVectorizer returned 2 vectors for 1 actions", error.getMessage());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void legacyRequestsUseSynthesizedActionIdTieBreaks() {
        List<SparseVector> vectors = List.of(
                new SparseVector(new int[]{30}),
                new SparseVector(new int[]{10}),
                new SparseVector(new int[]{20})
        );
        RankingRequest<String, String> request = new RankingRequest(
                "example",
                "shared",
                List.of("b", "a", "c")
        );
        RankingVectorizer<String, String> vectorizer = new RankingVectorizer<>() {
            @Override
            public List<SparseVector> apply(RankingRequest<String, String> rankingRequest) {
                return vectors;
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return null;
            }
        };
        GreedyRanker<String, String> greedyRanker = new GreedyRanker<>(vectorizer, ignored -> 1.0);

        RankingResponse<String> response = greedyRanker.rank(request);

        List<String> expectedActionIds = List.of("0", "1", "2").stream()
                .sorted(stableTieBreakOrder(request.exampleId()))
                .toList();
        Assertions.assertEquals(
                expectedActionIds,
                response.rankingDecisions().stream().map(RankingDecision::actionId).toList()
        );
        Assertions.assertEquals(
                expectedActionIds.stream().map(Integer::valueOf).toList(),
                response.rankingDecisions().stream().map(RankingDecision::actionIndex).toList()
        );
    }

    private Comparator<String> stableTieBreakOrder(String exampleId) {
        return (left, right) -> {
            int byTieBreakKey = RankingTieBreakers.compare(
                    RankingTieBreakers.stableTieBreakKey(exampleId, left),
                    RankingTieBreakers.stableTieBreakKey(exampleId, right)
            );
            return byTieBreakKey == 0 ? left.compareTo(right) : byTieBreakKey;
        };
    }

    private static Stream<RankingRequest<String, String>> requests() {
        return Stream.of(
                request("ex-1", null, List.of()),
                request("ex-2", "shared", List.of("a")),
                request("ex-3", "shared", List.of("a", "b", "c")),
                request("ex-4", "", List.of("same", "same", "different")),
                request("ex-5", "long shared value", List.of("sku-1", "sku-2", "sku-3", "sku-4", "sku-5"))
        );
    }

    private static RankingRequest<String, String> request(String exampleId, String sharedValue, List<String> actions) {
        return RankingRequest.ofAvailableActions(
                exampleId,
                sharedValue,
                withIndexedActionIds(actions)
        );
    }

    private static List<AvailableAction<String>> withIndexedActionIds(List<String> actions) {
        List<AvailableAction<String>> ret = new ArrayList<>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            ret.add(AvailableAction.of("action-" + i, actions.get(i)));
        }
        return Collections.unmodifiableList(ret);
    }

}
