package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingTransformerAuditEncoderTest {
    private record TestNamespace(String name) implements Namespace {
        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void given_include_feature_store_responses_writes_under_additional_properties() throws Exception {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformer<String, String> transformer = new RankingTransformer<>() {
            @Override
            public List<NamespacedRecord<Namespace, Object>> apply(com.hotvect.api.data.ranking.RankingRequest<String, String> rankingRequest) {
                NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                record.put(feature, 123);
                return ImmutableList.of(record);
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                TreeSet<Namespace> ret = new TreeSet<>(Namespace.alphabetical());
                ret.add(feature);
                return ret;
            }
        };

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

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions("a"),
                featureStoreResponseContainer
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(
                                RankingDecision.builder("a", "a").build(),
                                1.0
                        )
                )
        );

        RewardFunction<Double> rewardFunction = d -> d;
        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                transformer,
                rewardFunction,
                true
        );

        ByteBuffer buffer = encoder.apply(example);
        String json = new String(buffer.array(), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("additional_properties"));
        assertTrue(root.get("additional_properties").has("__feature_store_responses"));
        assertTrue(root.get("additional_properties").get("__feature_store_responses").has("view_1"));
        assertEquals("a", root.get("actions").get(0).get("action_id").asText());
    }

    @Test
    void given_flag_disabled_does_not_write_additional_properties() throws Exception {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformer<String, String> transformer = new RankingTransformer<>() {
            @Override
            public List<NamespacedRecord<Namespace, Object>> apply(com.hotvect.api.data.ranking.RankingRequest<String, String> rankingRequest) {
                NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                record.put(feature, 123);
                return ImmutableList.of(record);
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                TreeSet<Namespace> ret = new TreeSet<>(Namespace.alphabetical());
                ret.add(feature);
                return ret;
            }
        };

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions("a")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(
                                RankingDecision.builder("a", "a").build(),
                                1.0
                        )
                )
        );

        RewardFunction<Double> rewardFunction = d -> d;
        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                transformer,
                rewardFunction,
                false
        );

        ByteBuffer buffer = encoder.apply(example);
        String json = new String(buffer.array(), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        assertFalse(root.has("additional_properties"));
    }

    @Test
    void writes_request_additional_properties_without_feature_store_responses() throws Exception {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformer<String, String> transformer = simpleTransformer(feature);

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions("a"),
                Map.of("source", "request")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0))
        );

        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                transformer,
                d -> d,
                false
        );

        JsonNode root = new ObjectMapper().readTree(new String(encoder.apply(example).array(), StandardCharsets.UTF_8));
        assertEquals("request", root.get("additional_properties").get("source").asText());
    }

    @Test
    void joins_outcomes_by_action_id_when_outcome_order_differs() throws Exception {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformer<String, String> transformer = simpleTransformer(feature);

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions("a", "b")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0),
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0)
                )
        );

        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                transformer,
                d -> d,
                false
        );

        JsonNode root = new ObjectMapper().readTree(new String(encoder.apply(example).array(), StandardCharsets.UTF_8));
        assertEquals("a", root.get("actions").get(0).get("action_id").asText());
        assertEquals(1.0, root.get("actions").get(0).get("reward").asDouble());
        assertEquals("b", root.get("actions").get(1).get("action_id").asText());
        assertEquals(2.0, root.get("actions").get(1).get("reward").asDouble());
    }

    @Test
    void uses_transformed_action_ids_when_transformer_reorders_actions() throws Exception {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformer<String, String> transformer = new RankingTransformer<>() {
            @Override
            public List<TransformedAction<String>> transform(RankingRequest<String, String> rankingRequest) {
                return List.of(rankingRequest.actions().get(1), rankingRequest.actions().get(0)).stream().map(action -> {
                    NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                    record.put(feature, action.action());
                    return TransformedAction.of(action.actionId(), action.action(), record);
                }).toList();
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                TreeSet<Namespace> ret = new TreeSet<>(Namespace.alphabetical());
                ret.add(feature);
                return ret;
            }
        };

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions("a", "b")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("b", "b").build(), 2.0)
                )
        );

        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                transformer,
                d -> d,
                false
        );

        JsonNode root = new ObjectMapper().readTree(new String(encoder.apply(example).array(), StandardCharsets.UTF_8));
        assertEquals("b", root.get("actions").get(0).get("action_id").asText());
        assertEquals(2.0, root.get("actions").get(0).get("reward").asDouble());
        assertEquals("a", root.get("actions").get(1).get("action_id").asText());
        assertEquals(1.0, root.get("actions").get(1).get("reward").asDouble());
    }

    @Test
    void rejects_duplicate_outcome_action_ids() {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                simpleTransformer(feature),
                d -> d,
                false
        );

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions("a")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 2.0)
                )
        );

        assertEquals(
                "Example contains duplicate outcome for action id: a",
                assertThrows(IllegalArgumentException.class, () -> encoder.apply(example)).getMessage()
        );
    }

    @Test
    void rejects_unknown_outcome_action_ids() {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                simpleTransformer(feature),
                d -> d,
                false
        );

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions("a", "b")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(RankingDecision.builder("a", "a").build(), 1.0),
                        new RankingOutcome<>(RankingDecision.builder("c", "c").build(), 3.0)
                )
        );

        assertEquals(
                "Example contains outcome for unknown action id: c",
                assertThrows(IllegalArgumentException.class, () -> encoder.apply(example)).getMessage()
        );
    }

    @Test
    void rejects_too_few_transformed_actions() {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformer<String, String> transformer = transformerTransforming(feature, 1);
        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                transformer,
                d -> d,
                false
        );

        RankingExample<String, String, Double> example = example("a", "b");

        assertEquals(
                "RankingTransformer returned 1 transformed actions for 2 actions",
                assertThrows(IllegalArgumentException.class, () -> encoder.apply(example)).getMessage()
        );
    }

    @Test
    void rejects_too_many_transformed_actions() {
        Namespace feature = new TestNamespace("f_1");
        RankingTransformer<String, String> transformer = transformerTransforming(feature, 3);
        RankingTransformerAuditEncoder<String, String, Double> encoder = new RankingTransformerAuditEncoder<>(
                transformer,
                d -> d,
                false
        );

        RankingExample<String, String, Double> example = example("a", "b");

        assertEquals(
                "RankingTransformer returned 3 transformed actions for 2 actions",
                assertThrows(IllegalArgumentException.class, () -> encoder.apply(example)).getMessage()
        );
    }

    private RankingTransformer<String, String> simpleTransformer(Namespace feature) {
        return new RankingTransformer<>() {
            @Override
            public List<NamespacedRecord<Namespace, Object>> apply(com.hotvect.api.data.ranking.RankingRequest<String, String> rankingRequest) {
                return rankingRequest.availableActions().stream().map(ignored -> {
                    NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                    record.put(feature, 123);
                    return (NamespacedRecord<Namespace, Object>) record;
                }).toList();
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                TreeSet<Namespace> ret = new TreeSet<>(Namespace.alphabetical());
                ret.add(feature);
                return ret;
            }
        };
    }

    private RankingTransformer<String, String> transformerTransforming(Namespace feature, int count) {
        return new RankingTransformer<>() {
            @Override
            public List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<String, String> rankingRequest) {
                ImmutableList.Builder<NamespacedRecord<Namespace, Object>> transformed = ImmutableList.builder();
                for (int i = 0; i < count; i++) {
                    NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                    record.put(feature, 123);
                    transformed.add(record);
                }
                return transformed.build();
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                TreeSet<Namespace> ret = new TreeSet<>(Namespace.alphabetical());
                ret.add(feature);
                return ret;
            }
        };
    }

    private RankingExample<String, String, Double> example(String... actions) {
        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                RankingTestData.actions(actions)
        );
        return new RankingExample<>(
                "example_1",
                request,
                request.actions().stream()
                        .map(action -> new RankingOutcome<>(
                                RankingDecision.builder(action.actionId(), action.action()).build(),
                                1.0
                        ))
                        .toList()
        );
    }
}
