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
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.newOfflineRankingRequest(
                "example_1",
                "shared",
                ImmutableList.of("a"),
                featureStoreResponseContainer
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(
                                RankingDecision.builder(0, "a").build(),
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

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.newOfflineRankingRequest(
                "example_1",
                "shared",
                ImmutableList.of("a")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example_1",
                request,
                ImmutableList.of(
                        new RankingOutcome<>(
                                RankingDecision.builder(0, "a").build(),
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
}

