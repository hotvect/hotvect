package com.hotvect.offlineutils.export;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.*;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingResultFormatterTest {
    @Test
    void given_ranking_example_format_while_preserving_original_index() throws Exception {
        var testSubject = new RankingResultFormatter<Void, String, Double>();

        Ranker<Void, String> reverseRanker = new Ranker<>() {
            @Override
            public RankingResponse<String> rank(RankingRequest<Void, String> rankingRequest) {
                List<RankingDecision<String>> ret = new ArrayList<>();

                // This ranker ranks the input in reverse order so that we can test how the result formatter
                // restores the original order
                for (int i = rankingRequest.availableActions().size() - 1; i >= 0; i--) {
                    RankingDecision<String> decision = RankingDecision.builder(
                            rankingRequest.actions().get(i).actionId(),
                            i,
                            rankingRequest.availableActions().get(i)
                    ).build();
                    ret.add(decision);
                }
                return RankingResponse.newResponse(ret, ImmutableMap.of("log", "me"));
            }
        };

        var formatter = testSubject.apply(d -> d, reverseRanker);
        var actual = formatter.apply(
                new RankingExample<Void, String, Double>(
                        "example_1",
                        OfflineRankingRequest.ofAvailableActions(
                                "example_1",
                                null,
                                RankingTestData.actions("a", "b", "c"),
                                FeatureStoreResponseContainer.empty()
                        ),
                        ImmutableList.of(
                                new RankingOutcome<>(
                                        RankingDecision.builder("a", "a").build(),
                                        1.0
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder("b", "b").build(),
                                        2.0
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder("c", "c").build(),
                                        3.0
                                )
                        )
                )
        );

        assertEquals("{\"example_id\":\"example_1\",\"additional_properties\":{\"log\":\"me\"},\"result\":[{\"action_id\":\"a\",\"rank\":2,\"reward\":1.0},{\"action_id\":\"b\",\"rank\":1,\"reward\":2.0},{\"action_id\":\"c\",\"rank\":0,\"reward\":3.0}]}\n", new String(actual.array(), StandardCharsets.UTF_8));

    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void raw_action_request_format_uses_synthesized_action_ids() throws Exception {
        var testSubject = new RankingResultFormatter<Void, String, Double>();

        Ranker<Void, String> reverseRanker = rankingRequest -> {
            List<RankingDecision<String>> ret = new ArrayList<>();
            for (int i = rankingRequest.availableActions().size() - 1; i >= 0; i--) {
                ret.add(RankingDecision.builder(
                        rankingRequest.actions().get(i).actionId(),
                        i,
                        rankingRequest.availableActions().get(i)
                ).build());
            }
            return RankingResponse.newResponse(ret);
        };

        var formatter = testSubject.apply(d -> d, reverseRanker);
        OfflineRankingRequest<Void, String> request = OfflineRankingRequest.newOfflineRankingRequest(
                "example_1",
                null,
                (List) ImmutableList.of("a", "b", "c")
        );
        var actual = formatter.apply(
                new RankingExample<>(
                        "example_1",
                        request,
                        ImmutableList.of(
                                new RankingOutcome<>(RankingDecision.builder(0, "a").build(), 1.0),
                                new RankingOutcome<>(RankingDecision.builder(1, "b").build(), 2.0),
                                new RankingOutcome<>(RankingDecision.builder(2, "c").build(), 3.0)
                        )
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"0\",\"rank\":2,\"reward\":1.0},{\"action_id\":\"1\",\"rank\":1,\"reward\":2.0},{\"action_id\":\"2\",\"rank\":0,\"reward\":3.0}]}\n",
                new String(actual.array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void stable_action_request_accepts_legacy_action_index_ranker_decisions() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();

        Ranker<Void, String> reverseRanker = rankingRequest -> {
            List<RankingDecision<String>> ret = new ArrayList<>();
            for (int i = rankingRequest.availableActions().size() - 1; i >= 0; i--) {
                ret.add(RankingDecision.builder(i, rankingRequest.availableActions().get(i)).build());
            }
            return RankingResponse.newResponse(ret);
        };

        var formatter = testSubject.apply(d -> d, reverseRanker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("sku-a", "sku-b", "sku-c")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("sku-a", "sku-a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("sku-b", "sku-b").build(), 2.0),
                        new RankingOutcome<>(RankingDecision.builder("sku-c", "sku-c").build(), 3.0)
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"sku-a\",\"rank\":2,\"reward\":1.0},{\"action_id\":\"sku-b\",\"rank\":1,\"reward\":2.0},{\"action_id\":\"sku-c\",\"rank\":0,\"reward\":3.0}]}\n",
                new String(formatter.apply(example).array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void stable_action_request_accepts_action_id_ranker_decisions_without_action_index() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                ImmutableList.of(
                        RankingDecision.builder("b", "b").build(),
                        RankingDecision.builder("a", "a").build()
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0)
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"a\",\"rank\":1,\"reward\":1.0},{\"action_id\":\"b\",\"rank\":0,\"reward\":2.0}]}\n",
                new String(formatter.apply(example).array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void raw_action_request_outputs_domain_action_ids_when_decisions_have_action_index() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();

        Ranker<Void, String> reverseRanker = rankingRequest -> {
            List<RankingDecision<String>> ret = new ArrayList<>();
            for (int i = rankingRequest.availableActions().size() - 1; i >= 0; i--) {
                String action = rankingRequest.availableActions().get(i);
                ret.add(RankingDecision.builder(action, i, action).build());
            }
            return RankingResponse.newResponse(ret);
        };

        var formatter = testSubject.apply(d -> d, reverseRanker);
        OfflineRankingRequest<Void, String> request = OfflineRankingRequest.newOfflineRankingRequest(
                "example_1",
                null,
                (List) ImmutableList.of("sku-a", "sku-b", "sku-c")
        );
        var example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder(0, "sku-a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder(1, "sku-b").build(), 2.0),
                        new RankingOutcome<>(RankingDecision.builder(2, "sku-c").build(), 3.0)
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"sku-a\",\"rank\":2,\"reward\":1.0},{\"action_id\":\"sku-b\",\"rank\":1,\"reward\":2.0},{\"action_id\":\"sku-c\",\"rank\":0,\"reward\":3.0}]}\n",
                new String(formatter.apply(example).array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejects_ranker_decisions_without_action_index() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();

        Ranker<Void, String> reverseRanker = rankingRequest -> {
            List<RankingDecision<String>> ret = new ArrayList<>();
            for (int i = rankingRequest.availableActions().size() - 1; i >= 0; i--) {
                String action = rankingRequest.availableActions().get(i);
                ret.add(RankingDecision.builder(action, action).build());
            }
            return RankingResponse.newResponse(ret);
        };

        var formatter = testSubject.apply(d -> d, reverseRanker);
        OfflineRankingRequest<Void, String> request = OfflineRankingRequest.newOfflineRankingRequest(
                "example_1",
                null,
                (List) ImmutableList.of("sku-a", "sku-b", "sku-c")
        );
        var example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder(0, "sku-a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder(1, "sku-b").build(), 2.0),
                        new RankingOutcome<>(RankingDecision.builder(2, "sku-c").build(), 3.0)
                )
        );

        String message = assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage();
        assertTrue(message.startsWith("Ranker returned decision for unknown action id: sku-"));
    }

    @Test
    void action_id_alignment_rejects_unknown_ranker_action_ids() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                ImmutableList.of(
                        RankingDecision.builder("c", "c").build(),
                        RankingDecision.builder("b", "b").build()
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0)
                )
        );

        assertEquals(
                "Ranker returned decision for unknown action id: c",
                assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage()
        );
    }

    @Test
    void given_missing_outcome_omits_reward_but_keeps_ranking_order() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();

        Ranker<Void, String> reverseRanker = new Ranker<>() {
            @Override
            public RankingResponse<String> rank(RankingRequest<Void, String> rankingRequest) {
                List<RankingDecision<String>> ret = new ArrayList<>();
                for (int i = rankingRequest.availableActions().size() - 1; i >= 0; i--) {
                    ret.add(RankingDecision.builder(
                            rankingRequest.actions().get(i).actionId(),
                            i,
                            rankingRequest.availableActions().get(i)
                    ).build());
                }
                return RankingResponse.newResponse(ret);
            }
        };

        var formatter = testSubject.apply(d -> d, reverseRanker);
        RankingExample<Void, String, Double> example = new RankingExample<>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b", "c")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(
                                RankingDecision.builder("c", "c").build(),
                                3.0
                        ),
                        new RankingOutcome<>(
                                RankingDecision.builder("a", "a").build(),
                                1.0
                        )
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"a\",\"rank\":2,\"reward\":1.0},{\"action_id\":\"b\",\"rank\":1},{\"action_id\":\"c\",\"rank\":0,\"reward\":3.0}]}\n",
                new String(formatter.apply(example).array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void given_null_outcome_omits_reward() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = rankingRequest -> RankingResponse.newResponse(
                ImmutableList.of(
                        decision("a", 0, "a"),
                        decision("b", 1, "b")
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), null)
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"a\",\"rank\":0,\"reward\":1.0},{\"action_id\":\"b\",\"rank\":1}]}\n",
                new String(formatter.apply(example).array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void given_include_feature_store_responses_writes_under_additional_properties() throws Exception {
        var testSubject = new RankingResultFormatter<Void, String, Double>(true);

        FeatureStoreResponseContainer featureStoreResponseContainer = new FeatureStoreResponseContainer(
                ImmutableMap.of(
                        "view_1",
                        SimpleFeatureStoreResponse.builder()
                                .allEntities(
                                        ImmutableMap.of(
                                                ImmutableMap.of("entity_id", "e_1"),
                                                ImmutableMap.of("timestamp", Instant.parse("2026-02-06T00:00:00Z"))
                                        )
                                )
                                .build()
                )
        );

        Ranker<Void, String> ranker = new Ranker<>() {
            @Override
            public RankingResponse<String> rank(RankingRequest<Void, String> rankingRequest) {
                List<RankingDecision<String>> decisions = new ArrayList<>();
                decisions.add(RankingDecision.builder(
                        rankingRequest.actions().get(0).actionId(),
                        0,
                        rankingRequest.availableActions().get(0)
                ).build());
                return RankingResponse.newResponse(decisions, featureStoreResponseContainer, ImmutableMap.of());
            }
        };

        var formatter = testSubject.apply(d -> d, ranker);
        var actual = formatter.apply(
                new RankingExample<>(
                        "example_1",
                        OfflineRankingRequest.ofAvailableActions(
                                "example_1",
                                null,
                                RankingTestData.actions("a"),
                                FeatureStoreResponseContainer.empty()
                        ),
                        ImmutableList.of(
                                new RankingOutcome<>(
                                        RankingDecision.builder("a", "a").build(),
                                        1.0
                                )
                        )
                )
        );

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new String(actual.array(), StandardCharsets.UTF_8));
        assertTrue(root.has("additional_properties"));
        JsonNode additionalProperties = root.get("additional_properties");
        assertTrue(additionalProperties.has("__feature_store_responses"));
        assertTrue(additionalProperties.get("__feature_store_responses").has("view_1"));
    }

    @Test
    void rejects_incomplete_ranker_output() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                ImmutableList.of(decision("a", 0, "a"))
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0)
                )
        );

        assertEquals(
                "Ranker returned 1 decisions for 2 actions",
                assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage()
        );
    }

    @Test
    void rejects_duplicate_ranker_action_ids() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                ImmutableList.of(
                        decision("a", 0, "a"),
                        decision("a", 1, "a")
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0)
                )
        );

        assertEquals(
                "Ranker returned duplicate decision for action id: a",
                assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage()
        );
    }

    @Test
    void stable_action_request_rejects_indexed_decision_with_conflicting_action_id() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                ImmutableList.of(
                        decision("c", 0, "c"),
                        decision("b", 1, "b")
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0)
                )
        );

        assertEquals(
                "Ranker returned decision action id 'c' for action index 0; "
                        + "expected request action id 'a' or legacy positional action id '0'",
                assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage()
        );
    }

    @Test
    void stable_request_with_a_position_like_first_id_rejects_a_domain_action_id() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                ImmutableList.of(
                        decision("domain-zero", 0, "zero"),
                        decision("sku-b", 1, "sku-b")
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        List.of(AvailableAction.of("0", "zero"), AvailableAction.of("sku-b", "sku-b"))
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("0", "zero").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("sku-b", "sku-b").build(), 2.0)
                )
        );

        assertEquals(
                "Ranker returned decision action id 'domain-zero' for action index 0; "
                        + "expected request action id '0' or legacy positional action id '0'",
                assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage()
        );
    }

    @Test
    void joins_outcomes_by_action_id_when_outcome_order_differs() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = rankingRequest -> RankingResponse.newResponse(
                ImmutableList.of(
                        decision("b", 1, "b"),
                        decision("a", 0, "a")
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0),
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0)
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"a\",\"rank\":1,\"reward\":1.0},{\"action_id\":\"b\",\"rank\":0,\"reward\":2.0}]}\n",
                new String(formatter.apply(example).array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void rejects_duplicate_outcome_action_ids() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = rankingRequest -> RankingResponse.newResponse(
                ImmutableList.of(
                        decision("a", 0, "a"),
                        decision("b", 1, "b")
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 2.0)
                )
        );

        assertEquals(
                "Example contains duplicate outcome for action id: a",
                assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage()
        );
    }

    @Test
    void rejects_unknown_outcome_action_ids() {
        var testSubject = new RankingResultFormatter<Void, String, Double>();
        Ranker<Void, String> ranker = rankingRequest -> RankingResponse.newResponse(
                ImmutableList.of(
                        decision("a", 0, "a"),
                        decision("b", 1, "b")
                )
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var example = new RankingExample<Void, String, Double>(
                "example_1",
                OfflineRankingRequest.ofAvailableActions(
                        "example_1",
                        null,
                        RankingTestData.actions("a", "b")
                ),
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("c", "c").build(), 3.0)
                )
        );

        assertEquals(
                "Example contains outcome for unknown action id: c",
                assertThrows(IllegalArgumentException.class, () -> formatter.apply(example)).getMessage()
        );
    }

    private static <ACTION> RankingDecision<ACTION> decision(String actionId, int actionIndex, ACTION action) {
        return RankingDecision.builder(actionId, actionIndex, action).build();
    }

    private record ExampleOutcome(double outcome) {

        public Map<String, Object> getAdditionalProperties() {
                return ImmutableMap.of("outcome", outcome);
            }
        }

    private record ExampleAction(String id, Map<String, Object> additionalProperties) {

        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }
    }

    private record SharedWithAdditionalProperties(Map<String, Object> additionalProperties) {

        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }
    }

    @Test
    void propagates_request_additional_properties_to_root() throws Exception {
        var testSubject = new RankingResultFormatter<SharedWithAdditionalProperties, String, Double>();
        Ranker<SharedWithAdditionalProperties, String> ranker = rankingRequest -> RankingResponse.newResponse(
                List.of(decision("a", 0, "a")),
                Map.of("source", "ranker", "rankerOnly", "yes")
        );

        var formatter = testSubject.apply(d -> d, ranker);
        var actual = formatter.apply(
                new RankingExample<>(
                        "example_1",
                        OfflineRankingRequest.ofAvailableActions(
                                "example_1",
                                new SharedWithAdditionalProperties(Map.of(
                                        "source", "shared",
                                        "sharedOnly", "yes"
                                )),
                                RankingTestData.actions("a"),
                                Map.of("source", "request", "requestOnly", "yes")
                        ),
                        List.of(new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0))
                )
        );

        JsonNode additionalProperties = new ObjectMapper()
                .readTree(new String(actual.array(), StandardCharsets.UTF_8))
                .get("additional_properties");
        assertEquals("request", additionalProperties.get("source").asText());
        assertEquals("yes", additionalProperties.get("rankerOnly").asText());
        assertEquals("yes", additionalProperties.get("sharedOnly").asText());
        assertEquals("yes", additionalProperties.get("requestOnly").asText());
    }

    @Test
    void given_ranking_example_format_while_preserving_original_index_with_additional_properties() throws Exception {
        var testSubject = new RankingResultFormatter<Void, String, ExampleOutcome>();

        Ranker<Void, String> reverseRanker = new Ranker<>() {
            @Override
            public RankingResponse<String> rank(RankingRequest<Void, String> rankingRequest) {
                List<RankingDecision<String>> ret = new ArrayList<>();

                // This ranker ranks the input in reverse order so that we can test how the result formatter
                // restores the original order
                for (int i = rankingRequest.availableActions().size() - 1; i >= 0; i--) {
                    RankingDecision<String> decision = RankingDecision.builder(
                            rankingRequest.actions().get(i).actionId(),
                            i,
                            rankingRequest.availableActions().get(i)
                    ).build();
                    ret.add(decision);
                }
                return RankingResponse.newResponse(ret);
            }
        };

        var formatter = testSubject.apply(d -> d.outcome, reverseRanker);
        var actual = formatter.apply(
                new RankingExample<Void, String, ExampleOutcome>(
                        "example_1",
                        OfflineRankingRequest.ofAvailableActions(
                                "example_1",
                                null,
                                RankingTestData.actions("a", "b", "c")
                        ),
                        ImmutableList.of(
                                new RankingOutcome<>(
                                        RankingDecision.builder("a", "a").build(),
                                        new ExampleOutcome(1.0)
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder("b", "b").build(),
                                        new ExampleOutcome(2.0)
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder("c", "c").build(),
                                        new ExampleOutcome(3.0)
                                )
                        )
                )
        );

        assertEquals("{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"a\",\"rank\":2,\"reward\":1.0,\"additional_properties\":{\"outcome\":1.0}},{\"action_id\":\"b\",\"rank\":1,\"reward\":2.0,\"additional_properties\":{\"outcome\":2.0}},{\"action_id\":\"c\",\"rank\":0,\"reward\":3.0,\"additional_properties\":{\"outcome\":3.0}}]}\n", new String(actual.array(), StandardCharsets.UTF_8));

    }

    @Test
    void merges_action_available_action_and_decision_additional_properties() throws Exception {
        var testSubject = new RankingResultFormatter<Void, ExampleAction, ExampleOutcome>();
        ExampleAction action = new ExampleAction(
                "sku-a",
                Map.of("source", "action", "actionOnly", "yes")
        );
        Ranker<Void, ExampleAction> ranker = rankingRequest -> RankingResponse.newResponse(
                List.of(
                        RankingDecision.builder("sku-a", 0, action)
                                .withScore(1.0)
                                .withAdditionalProperties(Map.of("source", "decision", "decisionOnly", "yes"))
                                .build()
                )
        );

        var formatter = testSubject.apply(d -> d.outcome, ranker);
        var actual = formatter.apply(
                new RankingExample<>(
                        "example_1",
                        OfflineRankingRequest.ofAvailableActions(
                                "example_1",
                                null,
                                List.of(AvailableAction.of(
                                        "sku-a",
                                        action,
                                        Map.of("source", "request", "requestOnly", "yes")
                                ))
                        ),
                        List.of(new RankingOutcome<>(RankingDecision.builder("sku-a", action).build(), new ExampleOutcome(1.0)))
                )
        );

        JsonNode additionalProperties = new ObjectMapper()
                .readTree(new String(actual.array(), StandardCharsets.UTF_8))
                .get("result")
                .get(0)
                .get("additional_properties");
        assertEquals("decision", additionalProperties.get("source").asText());
        assertEquals(1.0, additionalProperties.get("outcome").asDouble());
        assertEquals("yes", additionalProperties.get("actionOnly").asText());
        assertEquals("yes", additionalProperties.get("requestOnly").asText());
        assertEquals("yes", additionalProperties.get("decisionOnly").asText());
    }

}
