package com.hotvect.core.rank;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BulkScoreGreedyRankerTest {

    @ParameterizedTest
    @MethodSource("rankingCases")
    void testRankingBehavior(List<String> actions, List<Double> scores) {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                withIndexedActionIds(actions)
        );
        FeatureStoreResponseContainer container = new FeatureStoreResponseContainer(
                ImmutableMap.of(
                        "view_1",
                        SimpleFeatureStoreResponse.builder().allEntities(ImmutableMap.of()).build()
                )
        );
        Map<String, Object> responseAdditionalProperties = ImmutableMap.of("response_prop", "1");
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                List<ScoringDecision<String>> decisions = new ArrayList<>(rankingRequest.availableActions().size());
                for (int i = 0; i < rankingRequest.availableActions().size(); i++) {
                    decisions.add(ScoringDecision.of(rankingRequest.availableActions().get(i), scores.get(i)));
                }
                return decisions;
            }

            @Override
            public BulkScoreResponse<String> score(RankingRequest<Void, String> rankingRequest) {
                return BulkScoreResponse.of(
                        bulkScore(rankingRequest),
                        container,
                        responseAdditionalProperties
                );
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<String> response = ranker.rank(request);
        List<RankingDecision<String>> decisions = response.rankingDecisions();

        assertEquals(actions.size(), decisions.size());

        // Extract the scores from decisions
        List<Double> decisionScores = new ArrayList<>(decisions.size());
        for (RankingDecision<String> d : decisions) {
            decisionScores.add(d.score());
        }

        // Sort the original scores in descending order
        List<Double> sortedScores = new ArrayList<>(scores.subList(0, actions.size()));
        sortedScores.sort(Collections.reverseOrder());

        // Compare sortedScores to decisionScores to ensure correct ordering
        assertEquals(sortedScores, decisionScores);
        assertEquals(container, response.featureStoreResponseContainer());
        assertEquals(responseAdditionalProperties, response.additionalProperties());
    }

    @Test
    void rejectsTooFewScores() {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-b", "b")
                )
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return List.of(ScoringDecision.of("a", 1.0));
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new BulkScoreGreedyRanker<>(scorer).rank(request)
        );
        assertEquals("BulkScorer returned 1 scores for 2 actions", error.getMessage());
    }

    @Test
    void rejectsTooManyScores() {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(AvailableAction.of("sku-a", "a"))
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return List.of(
                        ScoringDecision.of("a", 1.0),
                        ScoringDecision.of("extra", 2.0)
                );
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new BulkScoreGreedyRanker<>(scorer).rank(request)
        );
        assertEquals("BulkScorer returned 2 scores for 1 actions", error.getMessage());
    }

    @Test
    void rejectsTooManyLegacyApplyScores() {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(AvailableAction.of("sku-a", "a"))
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public DoubleArrayList apply(RankingRequest<Void, String> rankingRequest) {
                return DoubleArrayList.wrap(new double[]{1.0, 2.0});
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new BulkScoreGreedyRanker<>(scorer).rank(request)
        );
        assertEquals("BulkScorer returned 2 scores for 1 actions", error.getMessage());
    }

    @Test
    void closeDelegatesToBulkScorer() throws Exception {
        AtomicInteger closeCalls = new AtomicInteger(0);
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return List.of();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        ranker.close();
        assertEquals(1, closeCalls.get());
    }

    @Test
    void preservesActionIdFromActionModel() {
        record ActionWithId(String id) {
            public String getId() {
                return id;
            }
        }

        RankingRequest<Void, ActionWithId> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(new ActionWithId("sku-b"), new ActionWithId("sku-a")).stream()
                        .map(action -> AvailableAction.of(action.id(), action))
                        .toList()
        );
        BulkScorer<Void, ActionWithId> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<ActionWithId>> bulkScore(RankingRequest<Void, ActionWithId> rankingRequest) {
                return List.of(
                        ScoringDecision.of(rankingRequest.availableActions().get(0), 0.1),
                        ScoringDecision.of(rankingRequest.availableActions().get(1), 0.9)
                );
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<ActionWithId> response = ranker.rank(request);

        assertEquals("sku-a", response.rankingDecisions().get(0).actionId());
        assertEquals("sku-b", response.rankingDecisions().get(1).actionId());
    }

    @Test
    void rejectsScoringDecisionsReturnedOutOfRequestOrder() {
        assertTrue(assertionsEnabled());
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-b", "b"),
                        AvailableAction.of("sku-c", "c")
                )
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public BulkScoreResponse<String> score(RankingRequest<Void, String> rankingRequest) {
                return BulkScoreResponse.of(
                        List.of(
                                ScoringDecision.of("sku-b", "b", 0.9, ImmutableMap.of("source", "b")),
                                ScoringDecision.of("sku-c", "c", 0.1, ImmutableMap.of("source", "c")),
                                ScoringDecision.of("sku-a", "a", 0.5, ImmutableMap.of("source", "a"))
                        ),
                        FeatureStoreResponseContainer.empty()
                );
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new BulkScoreGreedyRanker<>(scorer).rank(request)
        );
        assertEquals(
                "BulkScorer must preserve request action order; score at position 0 has action id sku-b, expected sku-a",
                error.getMessage()
        );
    }

    @Test
    void rejectsMixedActionIdAndPositionalScores() {
        assertTrue(assertionsEnabled());
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-b", "b")
                )
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public BulkScoreResponse<String> score(RankingRequest<Void, String> rankingRequest) {
                return BulkScoreResponse.of(
                        List.of(
                                ScoringDecision.of("sku-a", "a", 0.9),
                                ScoringDecision.of("b", 0.1)
                        ),
                        FeatureStoreResponseContainer.empty()
                );
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new BulkScoreGreedyRanker<>(scorer).rank(request)
        );
        assertEquals("BulkScorer returned a mix of action-id and positional scores", error.getMessage());
    }

    @Test
    void rankMergesAvailableActionAndScoreAdditionalProperties() {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(AvailableAction.of("sku-a", "a", Map.of("source", "request", "requestOnly", "yes")))
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return List.of(ScoringDecision.of(
                        "sku-a",
                        "a",
                        1.0,
                        Map.of("source", "score", "scoreOnly", "yes")
                ));
            }
        };

        RankingResponse<String> response = new BulkScoreGreedyRanker<>(scorer).rank(request);

        assertEquals(
                Map.of("source", "score", "requestOnly", "yes", "scoreOnly", "yes"),
                response.rankingDecisions().get(0).additionalProperties()
        );
    }

    @Test
    void equalScoresAreTieBrokenByStableActionIdKey() {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(
                        AvailableAction.of("sku-b", "b"),
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-c", "c")
                )
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return rankingRequest.availableActions().stream()
                        .map(action -> ScoringDecision.of(action, 1.0))
                        .toList();
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<String> response = ranker.rank(request);

        List<String> expectedActionIds = request.actions().stream()
                .map(AvailableAction::actionId)
                .sorted(stableTieBreakOrder(request.exampleId()))
                .toList();
        assertEquals(
                expectedActionIds,
                response.rankingDecisions().stream().map(RankingDecision::actionId).toList()
        );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void legacyRequestsKeepPositionalActionIdsAndDecisionProperties() {
        RankingRequest<Void, String> request = new RankingRequest(
                "exampleId",
                null,
                List.of("b", "a")
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return List.of(
                        ScoringDecision.of("b", 0.1, ImmutableMap.of("source", "b")),
                        ScoringDecision.of("a", 0.9, ImmutableMap.of("source", "a"))
                );
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<String> response = ranker.rank(request);

        assertEquals(List.of("1", "0"), response.rankingDecisions().stream().map(RankingDecision::actionId).toList());
        assertEquals(List.of(1, 0), response.rankingDecisions().stream().map(RankingDecision::actionIndex).toList());
        assertEquals(List.of("a", "b"), response.rankingDecisions().stream().map(RankingDecision::action).toList());
        assertEquals("a", response.rankingDecisions().get(0).additionalProperties().get("source"));
        assertEquals("b", response.rankingDecisions().get(1).additionalProperties().get("source"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void legacyRequestsUseSynthesizedActionIdTieBreaks() {
        RankingRequest<Void, String> request = new RankingRequest(
                "exampleId",
                null,
                List.of("b", "a", "c")
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return rankingRequest.availableActions().stream()
                        .map(action -> ScoringDecision.of(action, 1.0))
                        .toList();
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<String> response = ranker.rank(request);

        List<String> expectedActionIds = List.of("0", "1", "2").stream()
                .sorted(stableTieBreakOrder(request.exampleId()))
                .toList();
        assertEquals(
                expectedActionIds,
                response.rankingDecisions().stream().map(RankingDecision::actionId).toList()
        );
        assertEquals(
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

    private static Stream<Arguments> rankingCases() {
        return Stream.of(
                Arguments.of(List.of("a"), List.of(1.0)),
                Arguments.of(List.of("a", "b", "c"), List.of(3.0, 1.0, 2.0)),
                Arguments.of(List.of("a", "b", "c"), List.of(1.0, 1.0, 1.0)),
                Arguments.of(List.of("a", "b", "c", "d"), List.of(-1.0, 0.0, 10.0, 2.0, 99.0))
        );
    }

    private List<AvailableAction<String>> withIndexedActionIds(List<String> actions) {
        List<AvailableAction<String>> ret = new ArrayList<>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            ret.add(AvailableAction.of("action-" + i, actions.get(i)));
        }
        return Collections.unmodifiableList(ret);
    }

    private static boolean assertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }
}
