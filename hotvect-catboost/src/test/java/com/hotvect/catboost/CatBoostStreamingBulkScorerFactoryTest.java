package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatBoostStreamingBulkScorerFactoryTest {
    @Test
    void missingModelParameterFailsWithExpectedKey() {
        CatBoostStreamingBulkScorerFactory<String, String> factory = new CatBoostStreamingBulkScorerFactory<>();
        StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
            @Override
            public java.util.stream.Stream<com.hotvect.api.data.ranking.TransformedAction<String>> transformStream(
                    com.hotvect.api.data.ranking.RankingRequest<String, String> request
            ) {
                return java.util.stream.Stream.empty();
            }

            @Override
            public com.hotvect.core.transform.ranking.PreparedBatchStream<String> prepareBatchStream(
                    com.hotvect.api.data.ranking.RankingRequest<String, String> request
            ) {
                return new com.hotvect.core.transform.ranking.PreparedBatchStream<>(java.util.stream.Stream.empty(), java.util.Map.of());
            }

            @Override
            public java.util.SortedSet<? extends com.hotvect.api.data.Namespace> getUsedFeatures() {
                return new java.util.TreeSet<>(com.hotvect.api.data.Namespace.alphabetical());
            }
        };

        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> factory.apply(transformer, Map.<String, InputStream>of(), Optional.<JsonNode>empty())
        );
        assertTrue(e.getMessage().contains("model_parameter/model.parameter"));
    }
}

