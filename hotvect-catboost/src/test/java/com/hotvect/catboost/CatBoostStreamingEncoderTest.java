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
import com.hotvect.core.transform.ranking.PreparedBatchStream;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                var action = request.actions().get(0);
                return Stream.of(TransformedAction.of(action.actionId(), action.action(), r));
            }

            @Override
            public PreparedBatchStream<String> prepareBatchStream(RankingRequest<String, String> request) {
                return new PreparedBatchStream<>(Stream.of(transformStream(request).toList()), java.util.Map.of());
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return used;
            }
        };

        RewardFunction<String> reward = _outcome -> 1.0;
        CatBoostStreamingEncoder<String, String, String> encoder = new CatBoostStreamingEncoder<>(transformer, reward);

        RankingRequest<String, String> req = RankingTestData.request("ex", "shared", "a1");
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

    @Test
    void usesOutcomePosition() {
        SortedSet<Namespace> used = new TreeSet<>(Namespace.alphabetical());
        used.add(TestNamespace.NUM);

        StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                return request.actions().stream().map(action -> {
                    NamespacedRecordImpl<Namespace, Object> r = new NamespacedRecordImpl<>();
                    r.put(TestNamespace.NUM, action.action().equals("a1") ? 10.0d : 20.0d);
                    return TransformedAction.of(action.actionId(), action.action(), r);
                });
            }

            @Override
            public PreparedBatchStream<String> prepareBatchStream(RankingRequest<String, String> request) {
                return new PreparedBatchStream<>(Stream.of(transformStream(request).toList()), java.util.Map.of());
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return used;
            }
        };

        CatBoostStreamingEncoder<String, String, Double> encoder = new CatBoostStreamingEncoder<>(transformer, outcome -> outcome);

        RankingRequest<String, String> req = RankingTestData.request("ex", "shared", "a1", "a2");
        List<RankingOutcome<Double, String>> outcomes = List.of(
                new RankingOutcome<>(RankingDecision.builder("a1", "a1").build(), 1.0),
                new RankingOutcome<>(RankingDecision.builder("a2", "a2").build(), 2.0)
        );
        RankingExample<String, String, Double> example = new RankingExample<>("ex", req, outcomes);

        String tsv = StandardCharsets.UTF_8.decode(encoder.apply(example)).toString();
        assertEquals("1\t10\n2\t20\n", tsv);
    }

    @Test
    void rejectsOutcomeOrderMismatch() {
        SortedSet<Namespace> used = new TreeSet<>(Namespace.alphabetical());
        used.add(TestNamespace.NUM);

        StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                return request.actions().stream().map(action -> {
                    NamespacedRecordImpl<Namespace, Object> r = new NamespacedRecordImpl<>();
                    r.put(TestNamespace.NUM, action.action().equals("a1") ? 10.0d : 20.0d);
                    return TransformedAction.of(action.actionId(), action.action(), r);
                });
            }

            @Override
            public PreparedBatchStream<String> prepareBatchStream(RankingRequest<String, String> request) {
                return new PreparedBatchStream<>(Stream.of(transformStream(request).toList()), java.util.Map.of());
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return used;
            }
        };

        CatBoostStreamingEncoder<String, String, Double> encoder = new CatBoostStreamingEncoder<>(transformer, outcome -> outcome);

        RankingRequest<String, String> req = RankingTestData.request("ex", "shared", "a1", "a2");
        List<RankingOutcome<Double, String>> outcomes = List.of(
                new RankingOutcome<>(RankingDecision.builder("a2", "a2").build(), 2.0),
                new RankingOutcome<>(RankingDecision.builder("a1", "a1").build(), 1.0)
        );
        RankingExample<String, String, Double> example = new RankingExample<>("ex", req, outcomes);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> encoder.apply(example));
        assertEquals("RankingExample outcome action id a2 at position 0, expected a1", error.getMessage());
    }

    @Test
    void rejectsTransformedActionOrderMismatch() {
        SortedSet<Namespace> used = new TreeSet<>(Namespace.alphabetical());
        used.add(TestNamespace.NUM);

        StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                return Stream.of(request.actions().get(1), request.actions().get(0)).map(action -> {
                    NamespacedRecordImpl<Namespace, Object> r = new NamespacedRecordImpl<>();
                    r.put(TestNamespace.NUM, action.action().equals("a1") ? 10.0d : 20.0d);
                    return TransformedAction.of(action.actionId(), action.action(), r);
                });
            }

            @Override
            public PreparedBatchStream<String> prepareBatchStream(RankingRequest<String, String> request) {
                return new PreparedBatchStream<>(Stream.of(transformStream(request).toList()), java.util.Map.of());
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return used;
            }
        };

        CatBoostStreamingEncoder<String, String, Double> encoder = new CatBoostStreamingEncoder<>(transformer, outcome -> outcome);

        RankingRequest<String, String> req = RankingTestData.request("ex", "shared", "a1", "a2");
        List<RankingOutcome<Double, String>> outcomes = List.of(
                new RankingOutcome<>(RankingDecision.builder("a1", "a1").build(), 1.0),
                new RankingOutcome<>(RankingDecision.builder("a2", "a2").build(), 2.0)
        );
        RankingExample<String, String, Double> example = new RankingExample<>("ex", req, outcomes);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> encoder.apply(example));
        assertEquals("RankingTransformer returned transformed action id a2 at position 0, expected a1", error.getMessage());
    }

}
