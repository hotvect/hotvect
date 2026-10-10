package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.Decision;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.common.Outcome;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.serve.ContractViolationException;
import com.hotvect.serve.SelectedRuntime;
import com.hotvect.serve.ServeApplication;
import com.hotvect.utils.AdditionalProperties;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Executes recorded offline examples against runtimes selected by the demo server. */
final class OfflineExampleExecutor {
    private static final ObjectMapper OM = new ObjectMapper();

    private final Function<String, SelectedRuntime> runtimeSelector;
    private final ActionMetadataLookup actionMetadata;
    private final Map<AlgorithmInstance<?>, ExampleDecoder<?>> decoders = new IdentityHashMap<>();
    private final ReentrantLock decodersLock = new ReentrantLock();

    OfflineExampleExecutor(ServeApplication app, ActionMetadataLookup actionMetadata) {
        this(Objects.requireNonNull(app)::selectRuntime, actionMetadata);
    }

    OfflineExampleExecutor(
            Function<String, SelectedRuntime> runtimeSelector,
            ActionMetadataLookup actionMetadata) {
        this.runtimeSelector = Objects.requireNonNull(runtimeSelector);
        this.actionMetadata = Objects.requireNonNull(actionMetadata);
    }

    ObjectNode runExample(
            ObjectNode caseNode,
            String expectedExampleIdOrNull,
            String algorithmRuntimeIdOrNull) throws Exception {
        SelectedRuntime selected = runtimeSelector.apply(algorithmRuntimeIdOrNull);
        ExampleDecoder<?> decoder = decoderFor(selected);
        String jsonString = OM.writeValueAsString(caseNode);
        List<?> decoded = decodeSingleExample(decoder, jsonString, expectedExampleIdOrNull);

        @SuppressWarnings("unchecked")
        Example<OfflineRequest<?>, ?> example = (Example<OfflineRequest<?>, ?>) decoded.getFirst();
        String decodedExampleId = requireNonEmptyExampleId(example.exampleId());
        if (expectedExampleIdOrNull != null && !Objects.equals(decodedExampleId, expectedExampleIdOrNull)) {
            throw new ContractViolationException(
                    "Decoded example_id does not match case JSON example_id",
                    "case=" + expectedExampleIdOrNull + ", decoded=" + decodedExampleId);
        }

        Object algorithm = selected.algorithmInstance().algorithm();
        ObjectNode response;
        if (algorithm instanceof Ranker<?, ?> ranker) {
            response = runRanker(decodedExampleId, ranker, (RankingRequest<?, ?>) example.request());
        } else if (algorithm instanceof TopK<?, ?> topK) {
            response = runTopK(decodedExampleId, topK, (TopKRequest<?>) example.request());
        } else {
            throw new ContractViolationException(
                    "Unsupported algorithm type: " + algorithm.getClass().getCanonicalName(), null);
        }
        addServingIdentity(response, selected);
        return response;
    }

    DecodedExampleId tryDecodeExampleIdOrNull(
            String caseJsonString,
            String algorithmRuntimeIdOrNull) throws Exception {
        SelectedRuntime selected = runtimeSelector.apply(algorithmRuntimeIdOrNull);
        try {
            List<?> decoded = decoderFor(selected).apply(caseJsonString);
            if (decoded == null || decoded.size() != 1) {
                return new DecodedExampleId(selected.identity().value(), null);
            }
            @SuppressWarnings("unchecked")
            Example<OfflineRequest<?>, ?> example = (Example<OfflineRequest<?>, ?>) decoded.getFirst();
            String exampleId = example.exampleId();
            return new DecodedExampleId(
                    selected.identity().value(),
                    exampleId == null || exampleId.isBlank() ? null : exampleId);
        } catch (RuntimeException ignored) {
            return new DecodedExampleId(selected.identity().value(), null);
        }
    }

    List<DecodedOnlineCandidate> decodeOnlineCandidates(
            String caseJsonString,
            String algorithmRuntimeIdOrNull) throws Exception {
        SelectedRuntime selected = runtimeSelector.apply(algorithmRuntimeIdOrNull);
        List<?> decoded = decodeSingleExample(decoderFor(selected), caseJsonString, null);
        @SuppressWarnings("unchecked")
        Example<OfflineRequest<?>, Outcome<?, ?>> example =
                (Example<OfflineRequest<?>, Outcome<?, ?>>) decoded.getFirst();
        List<Outcome<?, ?>> outcomes = example.outcomes();
        if (outcomes == null || outcomes.isEmpty()) {
            return List.of();
        }
        List<DecodedOnlineCandidate> candidates = new ArrayList<>(outcomes.size());
        for (int index = 0; index < outcomes.size(); index++) {
            Outcome<?, ?> outcome = outcomes.get(index);
            Decision<?> decision = outcome.decision();
            String actionId = actionIdOrNull(decision);
            if (actionId == null) {
                throw new ContractViolationException(
                        "Decoded online outcome is missing action_id", "outcome_index=" + index);
            }
            Map<String, Object> outcomeProperties = AdditionalProperties.getAdditionalProperties(outcome.outcome());
            Map<String, Object> onlineProperties = stringKeyMap(outcomeProperties.get("online"));
            if (!onlineProperties.isEmpty()) {
                candidates.add(new DecodedOnlineCandidate(
                        actionId,
                        decision.score(),
                        onlineProperties,
                        index));
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * Serving runs on virtual threads, where blocking on {@code synchronized} pins the carrier thread.
     * Decoder construction can be slow, so guard the cache with a lock that parks instead.
     */
    @SuppressWarnings("unchecked")
    private ExampleDecoder<?> decoderFor(SelectedRuntime selected) throws Exception {
        decodersLock.lock();
        try {
            ExampleDecoder<?> decoder = decoders.get(selected.algorithmInstance());
            if (decoder != null) {
                return decoder;
            }
            AlgorithmDefinition definition = selected.algorithmInstance().algorithmDefinition();
            if (definition.decoderFactoryName() == null) {
                throw new ContractViolationException(
                        "Algorithm definition missing decoder_factory_classname", null);
            }
            ClassLoader classLoader = selected.rootArtifactClassLoader();
            Object decoderFactory = classLoader.loadClass(definition.decoderFactoryName())
                    .getDeclaredConstructor()
                    .newInstance();
            if (decoderFactory instanceof ExampleDecoderFactory<?> factory) {
                decoder = factory.create(definition.testDecoderParameter());
            } else {
                decoder = ((Function<Optional<JsonNode>, ExampleDecoder<?>>) decoderFactory)
                        .apply(definition.testDecoderParameter());
            }
            decoders.put(selected.algorithmInstance(), decoder);
            return decoder;
        } finally {
            decodersLock.unlock();
        }
    }

    private static List<?> decodeSingleExample(
            ExampleDecoder<?> decoder,
            String caseJsonString,
            String expectedExampleIdOrNull) {
        List<?> decoded;
        try {
            decoded = decoder.apply(caseJsonString);
        } catch (RuntimeException error) {
            throw new ContractViolationException("Failed to decode request payload", error.getMessage());
        }
        if (decoded == null || decoded.isEmpty()) {
            throw new ContractViolationException(
                    "Decoder produced no examples for case",
                    expectedExampleIdOrNull == null ? null : "expected example_id=" + expectedExampleIdOrNull);
        }
        if (decoded.size() != 1) {
            throw new ContractViolationException(
                    "Decoder must produce exactly 1 example for a case; got " + decoded.size(), null);
        }
        return decoded;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ObjectNode runRanker(String exampleId, Ranker ranker, RankingRequest request) {
        RankingResponse<?> response = ranker.rank(request);
        List<RankingDecision<?>> decisions = (List<RankingDecision<?>>) response.decisions();
        List<String> actionIds = decisions.stream()
                .map(decision -> requireNonEmptyActionId(decision.actionId()))
                .distinct()
                .toList();
        Map<String, ActionMetadataLookup.ActionMetadata> metadataById =
                actionMetadata.getAllIfEnabled(actionIds);
        Map<String, Map<String, Object>> requestActionPropertiesById =
                actionPropertiesById(request.actions());

        ObjectNode root = OM.createObjectNode();
        root.put("type", "ranker");
        root.put("example_id", exampleId);
        root.set("additional_properties", OM.valueToTree(response.additionalProperties()));
        var decisionsNode = root.putArray("decisions");
        for (int rank = 0; rank < decisions.size(); rank++) {
            RankingDecision<?> decision = decisions.get(rank);
            String actionId = requireNonEmptyActionId(decision.actionId());
            Map<String, Object> additionalProperties = mergeAdditionalProperties(
                    requestActionPropertiesById.get(actionId),
                    decision.additionalProperties());
            ObjectNode node = decisionsNode.addObject();
            populateDecisionNode(
                    node,
                    rank,
                    actionId,
                    decision.score(),
                    decision.probability(),
                    additionalProperties,
                    actionMetadataFromProperties(actionId, additionalProperties, metadataById.get(actionId)));
        }
        return root;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ObjectNode runTopK(String exampleId, TopK topK, TopKRequest request) {
        TopKResponse<?> response = (TopKResponse<?>) topK.apply(request);
        List<TopKDecision<?>> decisions = (List<TopKDecision<?>>) response.decisions();
        List<String> actionIds = decisions.stream()
                .map(decision -> requireNonEmptyActionId(decision.actionId()))
                .distinct()
                .toList();
        Map<String, ActionMetadataLookup.ActionMetadata> metadataById =
                actionMetadata.getAllIfEnabled(actionIds);

        ObjectNode root = OM.createObjectNode();
        root.put("type", response instanceof ThemedTopKResponse ? "themed_topk" : "topk");
        root.put("example_id", exampleId);
        root.set("additional_properties", OM.valueToTree(response.additionalProperties()));
        if (response instanceof ThemedTopKResponse<?> themed) {
            root.put("action_list_id", themed.getActionListId());
            root.set("action_list_metadata", OM.valueToTree(themed.getActionListMetadata()));
        }
        var decisionsNode = root.putArray("decisions");
        for (int rank = 0; rank < decisions.size(); rank++) {
            TopKDecision<?> decision = decisions.get(rank);
            String actionId = requireNonEmptyActionId(decision.actionId());
            Map<String, Object> additionalProperties = mergeAdditionalProperties(
                    topKActionProperties(decision.action()),
                    decision.additionalProperties());
            ObjectNode node = decisionsNode.addObject();
            populateDecisionNode(
                    node,
                    rank,
                    actionId,
                    decision.score(),
                    decision.probability(),
                    additionalProperties,
                    actionMetadataFromProperties(actionId, additionalProperties, metadataById.get(actionId)));
        }
        return root;
    }

    private static void addServingIdentity(ObjectNode response, SelectedRuntime selected) {
        response.put("algorithm_id", selected.identity().algorithm().algorithmId().value());
        response.put("parameter_id", selected.identity().algorithm().parameterId());
        response.put("algorithm_runtime_id", selected.identity().value());
    }

    private static void populateDecisionNode(
            ObjectNode node,
            int rank,
            String actionId,
            Double score,
            Double probability,
            Map<String, Object> additionalProperties,
            ActionMetadataLookup.ActionMetadata metadata) {
        node.put("rank", rank);
        node.put("action_id", actionId);
        ActionMetadataJsonSupport.putActionDisplayMetadata(node, actionId, metadata);
        if (score != null) {
            node.put("score", score);
        }
        if (probability != null) {
            node.put("probability", probability);
        }
        node.set("additional_properties", OM.valueToTree(additionalProperties));
    }

    private static Map<String, Map<String, Object>> actionPropertiesById(
            List<? extends AvailableAction<?>> actions) {
        if (actions.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (AvailableAction<?> action : actions) {
            byId.put(action.actionId(), action.additionalProperties());
        }
        return byId;
    }

    private static Map<String, Object> topKActionProperties(Object action) {
        if (action == null) {
            return Map.of();
        }
        String className = action.getClass().getName();
        if (!className.equals("com.hotvect.core.transform.topk.AvailableAction")
                && !className.equals("com.hotvect.api.data.topk.AvailableAction")) {
            return Map.of();
        }
        try {
            Method method = action.getClass().getMethod("additionalProperties");
            return stringKeyMap(method.invoke(action));
        } catch (ReflectiveOperationException error) {
            throw new ContractViolationException(
                    "TopK AvailableAction is missing additionalProperties()",
                    "action_class=" + className);
        }
    }

    private static Map<String, Object> mergeAdditionalProperties(
            Map<String, Object> actionProperties,
            Map<String, Object> decisionProperties) {
        if ((actionProperties == null || actionProperties.isEmpty())
                && (decisionProperties == null || decisionProperties.isEmpty())) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (actionProperties != null) {
            merged.putAll(actionProperties);
        }
        if (decisionProperties != null) {
            merged.putAll(decisionProperties);
        }
        return merged;
    }

    private static ActionMetadataLookup.ActionMetadata actionMetadataFromProperties(
            String actionId,
            Map<String, Object> additionalProperties,
            ActionMetadataLookup.ActionMetadata lookupMetadata) {
        String actionName = stringProperty(additionalProperties, "action_name");
        String actionImageUrl = stringProperty(additionalProperties, "action_image_url");
        if (actionName == null && lookupMetadata != null) {
            actionName = lookupMetadata.actionName();
        }
        if (actionImageUrl == null && lookupMetadata != null) {
            actionImageUrl = lookupMetadata.actionImageUrl();
        }
        if (actionName == null && actionImageUrl == null && lookupMetadata == null) {
            return null;
        }
        return new ActionMetadataLookup.ActionMetadata(actionId, actionName, actionImageUrl);
    }

    private static String stringProperty(Map<String, Object> properties, String key) {
        Object value = properties == null ? null : properties.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String actionIdOrNull(Decision<?> decision) {
        if (decision == null || decision.actionId() == null || decision.actionId().isBlank()) {
            return null;
        }
        return decision.actionId();
    }

    private static String requireNonEmptyActionId(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            throw new ContractViolationException(
                    "Algorithm response is missing required action_id", null);
        }
        return actionId;
    }

    private static String requireNonEmptyExampleId(String exampleId) {
        if (exampleId == null || exampleId.isBlank()) {
            throw new ContractViolationException("Decoded example_id is missing/blank", null);
        }
        return exampleId;
    }

    private static Map<String, Object> stringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && !key.isBlank()) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    record DecodedExampleId(String algorithmRuntimeId, String exampleId) {
    }
}
