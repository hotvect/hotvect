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
import com.hotvect.core.transform.Computable;
import com.hotvect.core.transform.Computing;
import com.hotvect.core.transform.TransformationMetadata;
import com.hotvect.core.transform.ranking.ComputingCandidate;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatBoostEncoderParityTest {
    private enum TestNamespace implements Namespace {
        CAT {
            @Override
            public ValueType getFeatureValueType() {
                return CatBoostFeatureType.CATEGORICAL;
            }
        },
        NUM {
            @Override
            public ValueType getFeatureValueType() {
                return CatBoostFeatureType.NUMERICAL;
            }
        },
        TEXT {
            @Override
            public ValueType getFeatureValueType() {
                return CatBoostFeatureType.TEXT;
            }
        }
    }

    @Test
    void computingAndStreamingEncodersProduceIdenticalOutput() {
        SortedSet<Namespace> used = new TreeSet<>(Namespace.alphabetical());
        used.add(TestNamespace.CAT);
        used.add(TestNamespace.NUM);

        ComputingRankingTransformer<String, String> computingTransformer = new ComputingRankingTransformer<>() {
            @Override
            public SortedSet<Namespace> getUsedFeatures() {
                return used;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(String exampleId, String shared, List<Computable<String>> actions) {
                RankingRequest<String, String> rankingRequest = new RankingRequest<>(
                        exampleId,
                        shared,
                        actions.stream().map(Computable::getOriginalInput).toList()
                );
                return prepare(rankingRequest);
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(RankingRequest<String, String> rankingRequest) {
                Computing<RankingRequest<String, String>> computingShared = Computing.builder(rankingRequest).build();
                List<ComputingCandidate<String, String>> candidates = rankingRequest.availableActions().stream()
                        .map(action -> new ComputingCandidate<String, String>(
                                computingShared,
                                Computing.builder(action).build(),
                                null,
                                null,
                                null
                        ))
                        .toList();
                return new ComputingRankingRequest<>(rankingRequest, computingShared, candidates);
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(ComputingRankingRequest<String, String> computingRankingRequest) {
                return computingRankingRequest;
            }

            @Override
            public List<TransformedAction<String>> transform(ComputingRankingRequest<String, String> rankingRequest) {
                return rankingRequest.candidates().stream()
                        .map(candidate -> toTransformedAction(candidate.getOriginalInput().second()))
                        .toList();
            }

            @Override
            public List<TransformationMetadata> getTransformationMetadata() {
                return List.of();
            }
        };

        StreamingRankingTransformer<String, String> streamingTransformer = new StreamingRankingTransformer<>() {
            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                return request.availableActions().stream().map(CatBoostEncoderParityTest::toTransformedAction);
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
        CatBoostEncoder<String, String, String> computingEncoder = new CatBoostEncoder<>(computingTransformer, reward);
        CatBoostStreamingEncoder<String, String, String> streamingEncoder = new CatBoostStreamingEncoder<>(streamingTransformer, reward);

        RankingRequest<String, String> request = new RankingRequest<>("ex", "shared", List.of("a1", "a2"));
        List<RankingOutcome<String, String>> outcomes = List.of(
                new RankingOutcome<>(new RankingDecision<>("a1", 0, null, "a1", null, java.util.Map.of()), "clicked"),
                new RankingOutcome<>(new RankingDecision<>("a2", 1, null, "a2", null, java.util.Map.of()), "clicked")
        );
        RankingExample<String, String, String> example = new RankingExample<>("ex", request, outcomes);

        String computingTsv = StandardCharsets.UTF_8.decode(computingEncoder.apply(example)).toString();
        String streamingTsv = StandardCharsets.UTF_8.decode(streamingEncoder.apply(example)).toString();

        assertEquals(computingTsv, streamingTsv);
        assertEquals(computingEncoder.schemaDescription(), streamingEncoder.schemaDescription());
    }

    @Test
    void nullTextIsEncodedAsMissingTextSentinel() {
        SortedSet<Namespace> used = new TreeSet<>(Namespace.alphabetical());
        used.add(TestNamespace.TEXT);

        ComputingRankingTransformer<String, String> computingTransformer = new ComputingRankingTransformer<>() {
            @Override
            public SortedSet<Namespace> getUsedFeatures() {
                return used;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(String exampleId, String shared, List<Computable<String>> actions) {
                RankingRequest<String, String> rankingRequest = new RankingRequest<>(
                        exampleId,
                        shared,
                        actions.stream().map(Computable::getOriginalInput).toList()
                );
                return prepare(rankingRequest);
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(RankingRequest<String, String> rankingRequest) {
                Computing<RankingRequest<String, String>> computingShared = Computing.builder(rankingRequest).build();
                List<ComputingCandidate<String, String>> candidates = rankingRequest.availableActions().stream()
                        .map(action -> new ComputingCandidate<String, String>(
                                computingShared,
                                Computing.builder(action).build(),
                                null,
                                null,
                                null
                        ))
                        .toList();
                return new ComputingRankingRequest<>(rankingRequest, computingShared, candidates);
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(ComputingRankingRequest<String, String> computingRankingRequest) {
                return computingRankingRequest;
            }

            @Override
            public List<TransformedAction<String>> transform(ComputingRankingRequest<String, String> rankingRequest) {
                return rankingRequest.candidates().stream()
                        .map(candidate -> toTransformedActionWithNullText(candidate.getOriginalInput().second()))
                        .toList();
            }

            @Override
            public List<TransformationMetadata> getTransformationMetadata() {
                return List.of();
            }
        };

        StreamingRankingTransformer<String, String> streamingTransformer = new StreamingRankingTransformer<>() {
            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                return request.availableActions().stream().map(CatBoostEncoderParityTest::toTransformedActionWithNullText);
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
        CatBoostEncoder<String, String, String> computingEncoder = new CatBoostEncoder<>(computingTransformer, reward);
        CatBoostStreamingEncoder<String, String, String> streamingEncoder = new CatBoostStreamingEncoder<>(streamingTransformer, reward);

        RankingRequest<String, String> request = new RankingRequest<>("ex", "shared", List.of("a1"));
        List<RankingOutcome<String, String>> outcomes = List.of(
                new RankingOutcome<>(new RankingDecision<>("a1", 0, null, "a1", null, java.util.Map.of()), "clicked")
        );
        RankingExample<String, String, String> example = new RankingExample<>("ex", request, outcomes);

        String computingTsv = StandardCharsets.UTF_8.decode(computingEncoder.apply(example)).toString();
        String streamingTsv = StandardCharsets.UTF_8.decode(streamingEncoder.apply(example)).toString();

        assertEquals("1\t" + CatBoostEncodingUtils.MISSING_TEXT + "\n", computingTsv);
        assertEquals(computingTsv, streamingTsv);
    }

    private static TransformedAction<String> toTransformedAction(String action) {
        NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
        record.put(TestNamespace.CAT, action);
        record.put(TestNamespace.NUM, 2.5d);
        return TransformedAction.of(action, record);
    }

    private static TransformedAction<String> toTransformedActionWithNullText(String action) {
        NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
        record.put(TestNamespace.TEXT, null);
        return TransformedAction.of(action, record);
    }
}
