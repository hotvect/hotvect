package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

public final class ParityFixtureAlgorithm {
    private static final ActionContext ACTION_A = new ActionContext("action-a", 1.0, 0.10);
    private static final ActionContext ACTION_B = new ActionContext("action-b", -0.5, 1.20);
    private static final ActionContext ACTION_C = new ActionContext("action-c", 0.25, 0.70);

    private static final Namespace FEATURE_ACTION_ID = new TestNamespace("action_id");
    private static final Namespace FEATURE_BASE_VALUE = new TestNamespace("base_value");
    private static final Namespace FEATURE_SCORE = new TestNamespace("predicted_score");
    private static final Namespace FEATURE_BUCKET = new TestNamespace("bucket");

    private ParityFixtureAlgorithm() {
    }

    private static double score(SharedContext shared, ActionContext action) {
        return shared.baseValue() * action.multiplier() + action.bias();
    }

    public record SharedContext(String exampleId, double baseValue) {
    }

    public record ActionContext(String actionId, double multiplier, double bias) {
        public Map<String, Object> additionalProperties() {
            return ImmutableMap.of(
                    "action_id",
                    actionId,
                    "multiplier",
                    multiplier,
                    "bias",
                    bias
            );
        }

        @Override
        public String toString() {
            return actionId;
        }
    }

    private record TestNamespace(String name) implements Namespace {
        @Override
        public String toString() {
            return name;
        }
    }

    public static final class ExampleDecoderFactory implements RankingExampleDecoderFactory<SharedContext, ActionContext, Double> {
        @Override
        public RankingExampleDecoder<SharedContext, ActionContext, Double> apply(Optional<JsonNode> hyperparameter) {
            return encodedLine -> {
                String[] parts = encodedLine.strip().split("\\|");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Expected '<example_id>|<base_value>', got: " + encodedLine);
                }

                String exampleId = parts[0];
                double baseValue = Double.parseDouble(parts[1]);
                SharedContext shared = new SharedContext(exampleId, baseValue);
                ImmutableList<ActionContext> actions = ImmutableList.of(ACTION_A, ACTION_B, ACTION_C);

                ImmutableList<RankingOutcome<Double, ActionContext>> outcomes = ImmutableList.of(
                        new RankingOutcome<>(
                                RankingDecision.builder(ACTION_A.actionId(), 0, ACTION_A).build(),
                                baseValue + 0.05
                        ),
                        new RankingOutcome<>(
                                RankingDecision.builder(ACTION_B.actionId(), 1, ACTION_B).build(),
                                baseValue + 0.15
                        ),
                        new RankingOutcome<>(
                                RankingDecision.builder(ACTION_C.actionId(), 2, ACTION_C).build(),
                                baseValue + 0.25
                        )
                );

                return ImmutableList.of(
                        new RankingExample<>(
                                exampleId,
                                RankingRequest.ofAvailableActions(
                                        exampleId,
                                        shared,
                                        actions.stream()
                                                .map(action -> AvailableAction.of(action.actionId(), action))
                                                .toList()
                                ),
                                outcomes
                        )
                );
            };
        }
    }

    public static final class TransformerFactory implements RankingTransformerFactory<SharedContext, ActionContext> {
        @Override
        public RankingTransformer<SharedContext, ActionContext> apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameter) {
            return new RankingTransformer<>() {
                @Override
                public List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<SharedContext, ActionContext> rankingRequest) {
                    List<NamespacedRecord<Namespace, Object>> transformed = new ArrayList<>();
                    for (ActionContext action : rankingRequest.availableActions()) {
                        NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                        record.put(FEATURE_ACTION_ID, action.actionId());
                        record.put(FEATURE_BASE_VALUE, rankingRequest.shared().baseValue());
                        record.put(FEATURE_SCORE, score(rankingRequest.shared(), action));
                        record.put(
                                FEATURE_BUCKET,
                                rankingRequest.shared().baseValue() >= 1.5 ? "large" : "small"
                        );
                        transformed.add(record);
                    }
                    return transformed;
                }

                @Override
                public SortedSet<? extends Namespace> getUsedFeatures() {
                    TreeSet<Namespace> features = new TreeSet<>(Namespace.alphabetical());
                    features.add(FEATURE_ACTION_ID);
                    features.add(FEATURE_BASE_VALUE);
                    features.add(FEATURE_BUCKET);
                    features.add(FEATURE_SCORE);
                    return features;
                }
            };
        }
    }

    public static final class TestRewardFunctionFactory implements RewardFunctionFactory<Double> {
        @Override
        public RewardFunction<Double> get() {
            return value -> value;
        }
    }

    public static final class TestRankerFactory implements RankerFactory<RankingTransformer<SharedContext, ActionContext>, SharedContext, ActionContext> {
        @Override
        public Ranker<SharedContext, ActionContext> apply(
                RankingTransformer<SharedContext, ActionContext> ignoredDependency,
                Map<String, InputStream> parameters,
                Optional<JsonNode> hyperparameter
        ) {
            return request -> {
                List<RankingDecision<ActionContext>> decisions = new ArrayList<>();
                for (int i = 0; i < request.availableActions().size(); i++) {
                    ActionContext action = request.availableActions().get(i);
                    double score = score(request.shared(), action);
                    double probability = Math.min(0.99, 0.20 + (i * 0.10) + (request.shared().baseValue() / 20.0));
                    decisions.add(
                            RankingDecision.builder(action.actionId(), i, action)
                                    .withScore(score)
                                    .withProbability(probability)
                                    .withAdditionalProperties(ImmutableMap.of("fixture", "parity"))
                                    .build()
                    );
                }
                decisions.sort((left, right) -> Double.compare(right.score(), left.score()));
                return RankingResponse.newResponse(decisions, ImmutableMap.of("fixture", "parity"));
            };
        }
    }
}
