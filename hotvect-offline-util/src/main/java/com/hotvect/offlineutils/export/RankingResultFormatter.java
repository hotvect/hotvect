package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.hotvect.utils.AdditionalProperties.getAdditionalProperties;
import static com.hotvect.utils.AdditionalProperties.mergeAdditionalProperties;

/**
 * Formats ranking results in request-action order. Ranker responses can identify decisions either by action id
 * or by action index; this class aligns every decision back to its request action and resolves the action id that
 * should be exported. Stable {@link AvailableAction} requests keep request action ids, while legacy raw-action
 * requests can still export the ranker/domain action ids.
 */
public class RankingResultFormatter<SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, Ranker<SHARED, ACTION>, Function<RankingExample<SHARED, ACTION, OUTCOME>, ByteBuffer>> {
    private static final String FEATURE_STORE_RESPONSES_KEY = "__feature_store_responses";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final boolean includeFeatureStoreResponses;

    public RankingResultFormatter() {
        this(false);
    }

    public RankingResultFormatter(boolean includeFeatureStoreResponses) {
        this.includeFeatureStoreResponses = includeFeatureStoreResponses;
    }

    @Override
    public Function<RankingExample<SHARED, ACTION, OUTCOME>, ByteBuffer> apply(RewardFunction<OUTCOME> rewardFunction, Ranker<SHARED, ACTION> ranker) {
        return ex -> {
            var rankResult = ranker.rank(ex.request());
            var decisions = new ArrayList<>(rankResult.decisions());
            var actionIdToOutcome = RankingActionIds.outcomesByActionId(ex.outcomes());
            Set<String> requestActionIds = RankingActionIds.requestActionIds(ex.request().actions());
            var alignedDecisions = alignRankedDecisions(ex.request().actions(), decisions, requestActionIds);
            RankingActionIds.validateKnownActionIds(
                    "Example",
                    "outcome",
                    actionIdToOutcome.keySet(),
                    requestActionIds
            );

            ObjectNode root = objectMapper.createObjectNode();
            root.put("example_id", ex.exampleId());
            Map<String, Object> sharedAdditionalProperties = new HashMap<>();
            sharedAdditionalProperties.putAll(rankResult.additionalProperties());
            sharedAdditionalProperties.putAll(getAdditionalProperties(ex.request().shared()));
            sharedAdditionalProperties.putAll(ex.request().additionalProperties());
            if (includeFeatureStoreResponses) {
                sharedAdditionalProperties.put(
                        FEATURE_STORE_RESPONSES_KEY,
                        Objects.requireNonNull(rankResult.featureStoreResponseContainer(), "ranker returned null featureStoreResponseContainer")
                                .featureStoreResponses()
                );
            }
            if (!sharedAdditionalProperties.isEmpty()) {
                root.putPOJO("additional_properties", sharedAdditionalProperties);
            }


            ArrayNode rankToReward = objectMapper.createArrayNode();

            for (var availableAction : ex.request().actions()) {
                var actionId = availableAction.actionId();
                var alignedDecision = alignedDecisions.actionIdToAlignedDecision().get(actionId);
                var decision = alignedDecision.decision();
                var outcome = actionIdToOutcome.get(actionId);
                var score = decision.score();
                var probability = decision.probability();
                var result = objectMapper.createObjectNode();
                result.put("action_id", alignedDecision.outputActionId());
                result.put("rank", alignedDecision.rank());
                if (score != null) {
                    result.put("score", score);
                }
                if (probability != null) {
                    result.put("probability", probability);
                }
                Map<String, Object> outcomeAdditionalProperties = Collections.emptyMap();
                if (outcome != null && outcome.outcome() != null) {
                    var reward = rewardFunction.applyAsDouble(outcome.outcome());
                    result.put("reward", reward);
                    outcomeAdditionalProperties = getAdditionalProperties(outcome.outcome());
                }
                Map<String, Object> actionAdditionalProperties = getAdditionalProperties(availableAction.action());
                Map<String, Object> availableActionAdditionalProperties = availableAction.additionalProperties();
                Map<String, Object> decisionAdditionalProperties = decision.additionalProperties();

                // Extract and inline feature audit data if present
                if (decisionAdditionalProperties.containsKey("features")) {
                    Map<String, Object> featureAuditEntry = (Map<String, Object>) decisionAdditionalProperties.get("features");
                    ObjectNode featureAudit = objectMapper.createObjectNode();

                    for (Map.Entry<String, Object> algoEntry : featureAuditEntry.entrySet()) {
                        String algorithmName = algoEntry.getKey();
                        Map<String, Object> features = (Map<String, Object>) algoEntry.getValue();

                        // Create algorithm node with features
                        ObjectNode algorithmNode = objectMapper.createObjectNode();
                        algorithmNode.putPOJO("features", features);
                        featureAudit.set(algorithmName, algorithmNode);
                    }

                    // Inline feature_audit into this result object
                    result.set("feature_audit", featureAudit);

                    // Remove from additional properties so it doesn't appear there too
                    decisionAdditionalProperties = new HashMap<>(decisionAdditionalProperties);
                    decisionAdditionalProperties.remove("features");
                }

                Map<String, Object> merged = mergeAdditionalProperties(
                        outcomeAdditionalProperties,
                        actionAdditionalProperties,
                        availableActionAdditionalProperties,
                        decisionAdditionalProperties
                );
                if (!merged.isEmpty()) {
                    result.putPOJO("additional_properties", merged);
                }
                rankToReward.add(result);
            }
            root.set("result", rankToReward);
            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(root);
                byte[] withNewline = new byte[jsonBytes.length + 1];
                System.arraycopy(jsonBytes, 0, withNewline, 0, jsonBytes.length);
                withNewline[jsonBytes.length] = '\n';
                return ByteBuffer.wrap(withNewline);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private RankedDecisionAlignment<ACTION> alignRankedDecisions(
            List<AvailableAction<ACTION>> requestActions,
            List<RankingDecision<ACTION>> decisions,
            Set<String> requestActionIds
    ) {
        if (!decisions.isEmpty() && decisions.get(0).actionIndex() == -1) {
            return actionIdAlignment(requestActions.size(), decisions, requestActionIds);
        }

        boolean requestUsesSyntheticPositionIds = hasSyntheticPositionIds(requestActions);
        return actionIndexAlignment(requestActions, decisions, requestUsesSyntheticPositionIds);
    }

    private RankedDecisionAlignment<ACTION> actionIdAlignment(
            int requestActionCount,
            List<RankingDecision<ACTION>> decisions,
            Set<String> requestActionIds
    ) {
        var rankedDecisions = RankingActionIds.rankedDecisions(decisions);
        for (RankingDecision<ACTION> decision : rankedDecisions.actionIdToDecision().values()) {
            if (decision.actionIndex() != -1) {
                throw new IllegalArgumentException(
                        "Ranker mixed action-id and action-index decisions for action id: " + decision.actionId()
                );
            }
        }
        RankingActionIds.validateActionIdCoverage(
                "Ranker",
                "decision",
                rankedDecisions.actionIdToDecision().keySet(),
                requestActionIds,
                requestActionCount
        );
        Map<String, AlignedDecision<ACTION>> actionIdToAlignedDecision = new HashMap<>();
        for (Map.Entry<String, RankingDecision<ACTION>> entry : rankedDecisions.actionIdToDecision().entrySet()) {
            String actionId = entry.getKey();
            actionIdToAlignedDecision.put(
                    actionId,
                    new AlignedDecision<>(entry.getValue(), rankedDecisions.actionIdToRank().get(actionId), actionId)
            );
        }
        return new RankedDecisionAlignment<>(actionIdToAlignedDecision);
    }

    private RankedDecisionAlignment<ACTION> actionIndexAlignment(
            List<AvailableAction<ACTION>> requestActions,
            List<RankingDecision<ACTION>> decisions,
            boolean requestUsesSyntheticPositionIds
    ) {
        if (decisions.size() != requestActions.size()) {
            throw new IllegalArgumentException(
                    "Ranker returned " + decisions.size() + " decisions for " + requestActions.size() + " actions"
            );
        }

        Map<String, AlignedDecision<ACTION>> actionIdToAlignedDecision = new HashMap<>();
        Set<String> seenActionIds = new HashSet<>();
        boolean[] seenIndexes = new boolean[requestActions.size()];

        for (int rank = 0; rank < decisions.size(); rank++) {
            RankingDecision<ACTION> decision = decisions.get(rank);
            int actionIndex = decision.actionIndex();
            String decisionActionId = decision.actionId();
            if (actionIndex < 0 || actionIndex >= requestActions.size()) {
                throw new IllegalArgumentException(
                        "Ranker returned decision with invalid action index " + actionIndex
                                + " for action id: " + decisionActionId
                );
            }
            if (seenIndexes[actionIndex]) {
                throw new IllegalArgumentException(
                        "Ranker returned duplicate decision for action index: " + actionIndex
                );
            }
            seenIndexes[actionIndex] = true;
            if (!seenActionIds.add(decisionActionId)) {
                throw new IllegalArgumentException(
                        "Ranker returned duplicate decision for action id: " + decisionActionId
                );
            }

            String requestActionId = requestActions.get(actionIndex).actionId();
            if (!requestUsesSyntheticPositionIds) {
                validateStableRequestDecisionActionId(decisionActionId, requestActionId, actionIndex);
            }
            String outputActionId = requestUsesSyntheticPositionIds ? decisionActionId : requestActionId;
            actionIdToAlignedDecision.put(
                    requestActionId,
                    new AlignedDecision<>(decision, rank, outputActionId)
            );
        }
        return new RankedDecisionAlignment<>(actionIdToAlignedDecision);
    }

    private static void validateStableRequestDecisionActionId(
            String decisionActionId,
            String requestActionId,
            int actionIndex
    ) {
        if (decisionActionId.equals(requestActionId)) {
            return;
        }
        if (isLegacyPositionalActionId(decisionActionId, actionIndex)) {
            return;
        }
        throw new IllegalArgumentException(
                "Ranker returned decision action id '" + decisionActionId + "' for action index " + actionIndex
                        + "; expected request action id '" + requestActionId
                        + "' or legacy positional action id '" + actionIndex + "'"
        );
    }

    private static boolean isLegacyPositionalActionId(String actionId, int actionIndex) {
        return actionId.equals(String.valueOf(actionIndex));
    }

    private static <ACTION> boolean hasSyntheticPositionIds(List<AvailableAction<ACTION>> requestActions) {
        for (int actionIndex = 0; actionIndex < requestActions.size(); actionIndex++) {
            if (!requestActions.get(actionIndex).actionId().equals(String.valueOf(actionIndex))) {
                return false;
            }
        }
        return !requestActions.isEmpty();
    }

    private record AlignedDecision<ACTION>(
            RankingDecision<ACTION> decision,
            int rank,
            String outputActionId
    ) {
    }

    private record RankedDecisionAlignment<ACTION>(
            Map<String, AlignedDecision<ACTION>> actionIdToAlignedDecision
    ) {
    }
}
