package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CatBoostStreamingEncoderTest {
    private enum TestNamespace implements Namespace {
        NUM {
            @Override
            public ValueType getFeatureValueType() {
                return CatBoostFeatureType.NUMERICAL;
            }
        }
    }

    @Test
    void encodesSingleRowFromStreamingTransformer() {
        SortedSet<Namespace> used = new TreeSet<>(Namespace.alphabetical());
        used.add(TestNamespace.NUM);

        StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                NamespacedRecordImpl<Namespace, Object> r = new NamespacedRecordImpl<>();
                r.put(TestNamespace.NUM, 2.5d);
                return Stream.of(TransformedAction.of(request.availableActions().get(0), r));
            }

            @Override
            public Stream<List<TransformedAction<String>>> transformBatchStream(RankingRequest<String, String> request) {
                return Stream.of(transformStream(request).toList());
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return used;
            }
        };

        RewardFunction<String> reward = _outcome -> 1.0;
        CatBoostStreamingEncoder<String, String, String> encoder = new CatBoostStreamingEncoder<>(transformer, reward);

        RankingRequest<String, String> req = new RankingRequest<>("ex", "shared", List.of("a1"));
        List<RankingOutcome<String, String>> outcomes = List.of(
                new RankingOutcome<>(new RankingDecision<>("a1", 0, null, "a1", null, java.util.Map.of()), "clicked")
        );
        RankingExample<String, String, String> example = new RankingExample<>("ex", req, outcomes);

        String tsv = StandardCharsets.UTF_8.decode(encoder.apply(example)).toString();
        assertTrue(tsv.contains("\t"), "Expected at least one tab");
        assertTrue(tsv.endsWith("\n"), "Expected newline-terminated row");

        String schema = encoder.schemaDescription().orElseThrow();
        assertTrue(schema.contains("Label"));
        assertTrue(schema.contains("Num"));
        assertTrue(schema.contains(TestNamespace.NUM.toString()));
    }
}

