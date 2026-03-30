package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.audit.AuditableRankingVectorizer;
import com.hotvect.api.audit.RawFeatureName;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingAuditEncoderTest {
    private record TestFeatureNamespace(String name) implements com.hotvect.api.data.FeatureNamespace {
        @Override
        public ValueType getFeatureValueType() {
            return null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void given_include_feature_store_responses_writes_under_additional_properties() throws Exception {
        AuditableRankingVectorizer<String, String> vectorizer = new AuditableRankingVectorizer<>() {
            @Override
            public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit() {
                return ThreadLocal.withInitial(Collections::emptyMap);
            }

            @Override
            public List<SparseVector> apply(com.hotvect.api.data.ranking.RankingRequest<String, String> rankingRequest) {
                return ImmutableList.of(new SparseVector(new int[0], new int[]{0}, new double[]{1.0}));
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return new java.util.TreeSet<>(Namespace.alphabetical());
            }
        };

        FeatureStoreResponseContainer featureStoreResponseContainer = new FeatureStoreResponseContainer(
                ImmutableMap.of(
                        "view_1",
                        SimpleFeatureStoreResponse.success(
                                ImmutableMap.of(
                                        ImmutableMap.of("entity_id", "e_1"),
                                        ImmutableMap.of("timestamp", Instant.parse("2026-02-06T00:00:00Z"))
                                )
                        )
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
        RankingAuditEncoder<String, String, Double> encoder = new RankingAuditEncoder<>(
                vectorizer,
                rewardFunction,
                true
        );

        String json = encoder.apply(example);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("additional_properties"));
        assertTrue(root.get("additional_properties").has("__feature_store_responses"));
        assertTrue(root.get("additional_properties").get("__feature_store_responses").has("view_1"));
    }

    @Test
    void given_flag_disabled_does_not_write_additional_properties() throws Exception {
        AuditableRankingVectorizer<String, String> vectorizer = new AuditableRankingVectorizer<>() {
            @Override
            public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit() {
                return ThreadLocal.withInitial(Collections::emptyMap);
            }

            @Override
            public List<SparseVector> apply(com.hotvect.api.data.ranking.RankingRequest<String, String> rankingRequest) {
                return ImmutableList.of(new SparseVector(new int[0], new int[]{0}, new double[]{1.0}));
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return new java.util.TreeSet<>(Namespace.alphabetical());
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
        RankingAuditEncoder<String, String, Double> encoder = new RankingAuditEncoder<>(
                vectorizer,
                rewardFunction,
                false
        );

        String json = encoder.apply(example);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        assertFalse(root.has("additional_properties"));
    }
}

