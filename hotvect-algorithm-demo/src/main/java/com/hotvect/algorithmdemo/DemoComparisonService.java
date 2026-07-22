package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.algorithmserver.ActionMetadataLookup;
import com.hotvect.algorithmserver.ActionMetadataJsonSupport;
import com.hotvect.algorithmserver.AlgorithmServerApp;
import com.hotvect.algorithmserver.ContractViolationException;
import com.hotvect.algorithmserver.DecodedOnlineCandidate;
import com.hotvect.algorithmserver.JsonFieldSupport;
import com.hotvect.algorithmserver.JsonInStringSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DemoComparisonService {
    private static final ObjectMapper OM = new ObjectMapper();

    private final AlgorithmServerApp app;
    private final ActionMetadataRepository actionMetadata;

    DemoComparisonService(AlgorithmServerApp app, ActionMetadataRepository actionMetadata) {
        this.app = Objects.requireNonNull(app);
        this.actionMetadata = Objects.requireNonNull(actionMetadata);
    }

    ExampleViewData exampleViewData(ObjectNode exampleNode) throws JsonProcessingException {
        Objects.requireNonNull(exampleNode);
        List<ViewDefinition> algorithmViews = algorithmViews();
        List<OnlineCandidate> onlineCandidates = buildOnlineCandidates(exampleNode, referenceOnlineRuntime(algorithmViews));
        List<ViewDefinition> onlineViews = onlineViews(onlineCandidates, referenceOnlineRuntime(algorithmViews));
        ArrayNode views = comparisonViewDefinitions(onlineViews, algorithmViews);
        return new ExampleViewData(
                shownCandidatesFromOnlineCandidates(onlineCandidates),
                views,
                defaultViewIds(views));
    }

    ArrayNode comparisonViewDefinitions(ObjectNode exampleNode) throws JsonProcessingException {
        return exampleViewData(exampleNode).views();
    }

    ObjectNode compare(
            ObjectNode exampleNode,
            ExamplesRepository.ExampleRecord exampleRecord,
            List<String> requestedViewIds) throws Exception {
        JsonInStringSupport.collapseVirtualJsonFields(exampleNode);
        String expectedExampleId = JsonFieldSupport.nonEmptyStringField(exampleNode, "example_id").orElse(null);

        List<ViewDefinition> algorithmViews = algorithmViews();
        List<OnlineCandidate> onlineCandidates = buildOnlineCandidates(exampleNode, referenceOnlineRuntime(algorithmViews));
        List<ViewDefinition> onlineViews = onlineViews(onlineCandidates, referenceOnlineRuntime(algorithmViews));
        ArrayNode views = comparisonViewDefinitions(onlineViews, algorithmViews);
        List<String> selectedViewIds = resolveViewIds(views, requestedViewIds);
        Map<String, ViewDefinition> viewsById = new LinkedHashMap<>();
        for (ViewDefinition onlineView : onlineViews) {
            viewsById.put(onlineView.viewId(), onlineView);
        }
        for (ViewDefinition algorithmView : algorithmViews) {
            viewsById.put(algorithmView.viewId(), algorithmView);
        }

        ObjectNode root = OM.createObjectNode();
        if (exampleRecord != null) {
            root.put("example_index", exampleRecord.id());
            root.put("example_source", exampleRecord.source());
        }
        root.set("views", views);
        root.set("default_view_ids", OM.valueToTree(defaultViewIds(views)));

        ArrayNode runs = root.putArray("runs");
        for (String viewId : selectedViewIds) {
            runs.add(runView(viewId, exampleNode, expectedExampleId, onlineCandidates, viewsById));
        }
        root.set("response", outputResponseFromRuns(runs, exampleNode));
        return root;
    }

    ObjectNode projectResponse(ObjectNode exampleNode, String algorithmRuntimeIdOrNull) throws Exception {
        JsonInStringSupport.collapseVirtualJsonFields(exampleNode);
        String expectedExampleId = JsonFieldSupport.nonEmptyStringField(exampleNode, "example_id").orElse(null);
        ObjectNode run = app.runExample(exampleNode, expectedExampleId, algorithmRuntimeIdOrNull);
        fillDecisionMetadataFallbacks(run);
        return demoResponseFromRun(run, exampleNode);
    }

    static ObjectNode outputResponseFromRuns(ArrayNode runs, ObjectNode exampleNode) {
        ObjectNode selected = null;
        for (JsonNode run : runs) {
            if (run instanceof ObjectNode runObject && "algorithm".equals(JsonFieldSupport.textOrNull(runObject.get("kind")))) {
                selected = runObject;
                break;
            }
        }
        if (selected == null) {
            for (JsonNode run : runs) {
                if (run instanceof ObjectNode runObject && "online.impression".equals(JsonFieldSupport.textOrNull(runObject.get("view_id")))) {
                    selected = runObject;
                    break;
                }
            }
        }
        if (selected == null) {
            for (JsonNode run : runs) {
                if (run instanceof ObjectNode runObject) {
                    selected = runObject;
                    break;
                }
            }
        }
        return selected == null ? OM.createObjectNode() : demoResponseFromRun(selected, exampleNode);
    }

    static ObjectNode demoResponseFromRun(ObjectNode run, ObjectNode exampleNode) {
        ObjectNode response = OM.createObjectNode();
        JsonFieldSupport.nonEmptyStringField(exampleNode, "request_id")
                .or(() -> JsonFieldSupport.nonEmptyStringField(exampleNode, "relevance_id"))
                .ifPresent(value -> response.put("request_id", value));
        JsonFieldSupport.nonEmptyStringField(exampleNode, "variant_id").ifPresent(value -> response.put("variant_id", value));
        response.set("processed_candidates", processedCandidatesFromRun(run));
        response.set("extra_data", responseExtraDataFromRun(run));
        return response;
    }

    private ArrayNode comparisonViewDefinitions(List<ViewDefinition> onlineViews, List<ViewDefinition> algorithmViews) {
        ArrayNode views = OM.createArrayNode();
        for (ViewDefinition onlineView : onlineViews) {
            views.add(onlineView.toJson());
        }
        for (ViewDefinition algorithmView : algorithmViews) {
            views.add(algorithmView.toJson());
        }
        return views;
    }

    private List<ViewDefinition> algorithmViews() {
        ObjectNode metadata = app.buildMetadata();
        List<ViewDefinition> views = new ArrayList<>();
        JsonNode runtimes = metadata.get("runtimes");
        if (runtimes instanceof ArrayNode runtimeArray && !runtimeArray.isEmpty()) {
            for (JsonNode runtimeNode : runtimeArray) {
                ViewDefinition view = algorithmView(runtimeNode, JsonFieldSupport.textOrNull(runtimeNode.get("algorithm_runtime_id")));
                if (view != null) {
                    views.add(view);
                }
            }
            return List.copyOf(views);
        }

        ViewDefinition view = algorithmView(metadata, null);
        if (view != null) {
            views.add(view);
        }
        return List.copyOf(views);
    }

    private ViewDefinition algorithmView(JsonNode node, String selectionAlgorithmRuntimeIdOrNull) {
        String viewId = JsonFieldSupport.textOrNull(node.get("algorithm_runtime_id"));
        if (viewId == null) {
            return null;
        }
        JsonNode algorithmNode = node.get("algorithm");
        JsonNode parametersNode = node.get("parameters");
        String algorithmName = JsonFieldSupport.textOrNull(algorithmNode == null ? null : algorithmNode.get("name"));
        String algorithmVersion = JsonFieldSupport.textOrNull(algorithmNode == null ? null : algorithmNode.get("version"));
        String parameterId = JsonFieldSupport.textOrNull(parametersNode == null ? null : parametersNode.get("parameter_id"));
        String title = algorithmRuntimeTitle(viewId, algorithmName, algorithmVersion, parameterId);
        return new ViewDefinition(viewId, "algorithm", title, selectionAlgorithmRuntimeIdOrNull, viewId, algorithmName, algorithmVersion, parameterId);
    }

    private static String algorithmRuntimeTitle(String viewId, String algorithmName, String algorithmVersion, String parameterId) {
        if (algorithmName != null && algorithmVersion != null && parameterId != null) {
            return algorithmName + "@" + algorithmVersion + " / " + parameterId;
        }
        return viewId;
    }

    private ViewDefinition referenceOnlineRuntime(List<ViewDefinition> algorithmViews) {
        return algorithmViews.isEmpty() ? null : algorithmViews.getFirst();
    }

    private List<ViewDefinition> onlineViews(List<OnlineCandidate> onlineCandidates, ViewDefinition runtime) {
        if (runtime == null) {
            return List.of();
        }
        List<ViewDefinition> views = new ArrayList<>();
        for (String viewId : onlineViewIds(onlineCandidates)) {
            views.add(new ViewDefinition(
                    viewId,
                    "online",
                    viewId,
                    runtime.selectionAlgorithmRuntimeIdOrNull(),
                    runtime.algorithmRuntimeId(),
                    runtime.algorithmName(),
                    runtime.algorithmVersion(),
                    runtime.parameterId()));
        }
        return List.copyOf(views);
    }

    private List<OnlineCandidate> buildOnlineCandidates(ObjectNode exampleNode, ViewDefinition runtime) throws JsonProcessingException {
        List<DecodedOnlineCandidate> decoded = app.decodeOnlineCandidates(
                OM.writeValueAsString(exampleNode),
                runtime == null ? null : runtime.selectionAlgorithmRuntimeIdOrNull());
        return onlineCandidatesFromDecoded(decoded);
    }

    private ObjectNode runView(
            String viewId,
            ObjectNode exampleNode,
            String expectedExampleId,
            List<OnlineCandidate> onlineCandidates,
            Map<String, ViewDefinition> viewsById) throws Exception {
        if (viewId == null || viewId.isBlank()) {
            throw new ContractViolationException("view_id must not be blank", null);
        }
        ViewDefinition view = viewsById.get(viewId);
        if (view == null) {
            throw new ContractViolationException("Unknown view_id", viewId);
        }
        if ("online".equals(view.kind())) {
            app.runExample(exampleNode, expectedExampleId, view.selectionAlgorithmRuntimeIdOrNull());
            return onlineRun(view, onlineCandidates);
        }
        ObjectNode response = app.runExample(exampleNode, expectedExampleId, view.selectionAlgorithmRuntimeIdOrNull());
        fillDecisionMetadataFallbacks(response);
        response.put("view_id", view.viewId());
        response.put("kind", "algorithm");
        response.put("title", view.title());
        response.put("algorithm_runtime_id", view.algorithmRuntimeId());
        JsonFieldSupport.putStringOrNull(response, "algorithm_name", view.algorithmName());
        JsonFieldSupport.putStringOrNull(response, "algorithm_version", view.algorithmVersion());
        JsonFieldSupport.putStringOrNull(response, "parameter_id", view.parameterId());
        return response;
    }

    private ObjectNode onlineRun(ViewDefinition view, List<OnlineCandidate> onlineCandidates) {
        String viewId = view.viewId();
        List<OnlineCandidate> selected = onlineCandidates.stream()
                .filter(candidate -> candidate.metricsByView().containsKey(viewId))
                .sorted(Comparator
                        .comparingDouble((OnlineCandidate candidate) -> firstFiniteOrDefault(candidate.metricsByView().get(viewId).rank(), null, Double.POSITIVE_INFINITY))
                        .thenComparingInt(OnlineCandidate::originalIndex))
                .toList();
        Set<String> actionIds = new LinkedHashSet<>();
        for (OnlineCandidate candidate : selected) {
            actionIds.add(candidate.actionId());
        }
        Map<String, ActionMetadataRepository.ActionMetadata> metas = actionMetadata.getAllIfEnabled(actionIds);

        ObjectNode root = OM.createObjectNode();
        root.put("view_id", viewId);
        root.put("kind", "online");
        root.put("title", viewId);
        root.put("type", "online");
        root.put("algorithm_runtime_id", view.algorithmRuntimeId());
        JsonFieldSupport.putStringOrNull(root, "algorithm_name", view.algorithmName());
        JsonFieldSupport.putStringOrNull(root, "algorithm_version", view.algorithmVersion());
        JsonFieldSupport.putStringOrNull(root, "parameter_id", view.parameterId());
        root.set("additional_properties", OM.createObjectNode());
        ArrayNode decisions = root.putArray("decisions");
        String dimension = viewId.substring("online.".length());
        for (OnlineCandidate candidate : selected) {
            OnlineMetric metric = candidate.metricsByView().get(viewId);
            ObjectNode decision = decisions.addObject();
            JsonFieldSupport.putNumberOrNull(decision, "rank", metric.rank());
            decision.put("action_id", candidate.actionId());
            putResolvedActionMetadata(decision, candidate.actionId(), metas, true);
            JsonFieldSupport.putNumberOrNull(decision, "score", metric.score());
            ObjectNode additionalProperties = decision.putObject("additional_properties");
            ObjectNode onlineNode = additionalProperties.putObject("online");
            onlineNode.set(dimension, metric.properties().deepCopy());
        }
        return root;
    }

    private ArrayNode shownCandidatesFromOnlineCandidates(List<OnlineCandidate> onlineCandidates) {
        String viewId = shownCandidateViewId(onlineCandidates);
        ArrayNode out = OM.createArrayNode();
        if (viewId == null) {
            return out;
        }
        List<OnlineCandidate> selected = onlineCandidates.stream()
                .filter(candidate -> candidate.metricsByView().containsKey(viewId))
                .sorted(Comparator
                        .comparingDouble((OnlineCandidate candidate) -> firstFiniteOrDefault(candidate.metricsByView().get(viewId).rank(), null, Double.POSITIVE_INFINITY))
                        .thenComparingInt(OnlineCandidate::originalIndex))
                .toList();
        Set<String> actionIds = new LinkedHashSet<>();
        for (OnlineCandidate candidate : selected) {
            actionIds.add(candidate.actionId());
        }
        Map<String, ActionMetadataRepository.ActionMetadata> metas = actionMetadata.getAllIfEnabled(actionIds);
        for (OnlineCandidate candidate : selected) {
            OnlineMetric metric = candidate.metricsByView().get(viewId);
            ObjectNode node = out.addObject();
            node.put("action_id", candidate.actionId());
            node.put("view_id", viewId);
            JsonFieldSupport.putNumberOrNull(node, "shown_position", metric.rank());
            JsonFieldSupport.putNumberOrNull(node, "score", metric.score());
            putResolvedActionMetadata(node, candidate.actionId(), metas, true);
        }
        return out;
    }

    private static String shownCandidateViewId(List<OnlineCandidate> onlineCandidates) {
        for (String preferred : List.of("online.impression", "online.algorithm", "online.request")) {
            for (OnlineCandidate candidate : onlineCandidates) {
                if (candidate.metricsByView().containsKey(preferred)) {
                    return preferred;
                }
            }
        }
        return onlineViewIds(onlineCandidates).stream().findFirst().orElse(null);
    }

    private void fillDecisionMetadataFallbacks(ObjectNode run) {
        if (!actionMetadata.isEnabled()) {
            return;
        }
        JsonNode decisionsNode = run.get("decisions");
        if (!(decisionsNode instanceof ArrayNode decisions)) {
            return;
        }
        Set<String> missingActionIds = new LinkedHashSet<>();
        for (JsonNode node : decisions) {
            if (!(node instanceof ObjectNode decision)) {
                continue;
            }
            String actionId = JsonFieldSupport.textOrNull(decision.get("action_id"));
            if (actionId == null) {
                continue;
            }
            boolean missingName = JsonFieldSupport.textOrNull(decision.get("action_name")) == null;
            boolean missingImage = JsonFieldSupport.textOrNull(decision.get("action_image_url")) == null;
            if (missingName || missingImage) {
                missingActionIds.add(actionId);
            }
        }
        if (missingActionIds.isEmpty()) {
            return;
        }
        Map<String, ActionMetadataRepository.ActionMetadata> found = actionMetadata.getAllIfEnabled(missingActionIds);
        for (JsonNode node : decisions) {
            if (!(node instanceof ObjectNode decision)) {
                continue;
            }
            String actionId = JsonFieldSupport.textOrNull(decision.get("action_id"));
            if (actionId == null) {
                continue;
            }
            fillMissingActionMetadata(decision, actionId, resolvedActionMetadata(found, actionId));
        }
    }

    static ArrayNode processedCandidatesFromRun(ObjectNode run) {
        ArrayNode processed = OM.createArrayNode();
        JsonNode decisionsValue = run.get("decisions");
        if (!(decisionsValue instanceof ArrayNode decisions)) {
            return processed;
        }
        for (JsonNode value : decisions) {
            if (value instanceof ObjectNode decision) {
                String actionId = JsonFieldSupport.textOrNull(decision.get("action_id"));
                if (actionId != null) {
                    ObjectNode candidate = processed.addObject();
                    candidate.put("id", actionId);
                }
            }
        }
        return processed;
    }

    static JsonNode responseExtraDataFromRun(ObjectNode run) {
        JsonNode actionListMetadata = run.get("action_list_metadata");
        if (actionListMetadata == null || actionListMetadata.isNull()) {
            return OM.createObjectNode();
        }
        return actionListMetadata.deepCopy();
    }

    static List<String> onlineViewIds(List<OnlineCandidate> onlineCandidates) {
        Set<String> seen = new LinkedHashSet<>();
        for (String preferred : List.of("online.request", "online.algorithm", "online.impression")) {
            for (OnlineCandidate candidate : onlineCandidates) {
                if (candidate.metricsByView().containsKey(preferred)) {
                    seen.add(preferred);
                    break;
                }
            }
        }
        List<String> discovered = new ArrayList<>();
        for (OnlineCandidate candidate : onlineCandidates) {
            for (String viewId : candidate.metricsByView().keySet()) {
                if (!seen.contains(viewId)) {
                    discovered.add(viewId);
                }
            }
        }
        discovered.stream().sorted().forEach(seen::add);
        return List.copyOf(seen);
    }

    static List<OnlineCandidate> onlineCandidatesFromDecoded(List<DecodedOnlineCandidate> decoded) {
        List<OnlineCandidate> out = new ArrayList<>(decoded.size());
        for (DecodedOnlineCandidate candidate : decoded) {
            Map<String, OnlineMetric> metrics = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : stringKeyMap(candidate.onlineProperties()).entrySet()) {
                Map<String, Object> values = stringKeyMap(entry.getValue());
                Double rank = numberFromObject(values.get("rank"));
                if (rank == null) {
                    continue;
                }
                Double score = numberFromObject(values.get("score"));
                if (score == null) {
                    score = candidate.score();
                }
                ObjectNode properties = OM.createObjectNode();
                JsonFieldSupport.putNumberOrNull(properties, "rank", rank);
                JsonFieldSupport.putNumberOrNull(properties, "score", score);
                metrics.put("online." + entry.getKey(), new OnlineMetric(rank, score, properties));
            }
            if (!metrics.isEmpty()) {
                out.add(new OnlineCandidate(candidate.actionId(), candidate.originalIndex(), metrics));
            }
        }
        return out;
    }

    static List<String> defaultViewIds(ArrayNode views) {
        String firstAlgorithm = firstViewId(views, "algorithm", null);
        String secondAlgorithm = secondViewId(views, "algorithm");
        String onlineImpression = firstViewId(views, "online", "online.impression");
        String firstOnline = firstViewId(views, "online", null);
        if (firstAlgorithm != null && onlineImpression != null) {
            return List.of(firstAlgorithm, onlineImpression);
        }
        if (firstAlgorithm != null && firstOnline != null) {
            return List.of(firstAlgorithm, firstOnline);
        }
        if (firstAlgorithm != null && secondAlgorithm != null) {
            return List.of(firstAlgorithm, secondAlgorithm);
        }
        if (firstAlgorithm != null) {
            return List.of(firstAlgorithm);
        }
        if (firstOnline != null) {
            String secondOnline = secondViewId(views, "online");
            return secondOnline == null ? List.of(firstOnline) : List.of(firstOnline, secondOnline);
        }
        List<String> selected = new ArrayList<>();
        for (JsonNode view : views) {
            String viewId = JsonFieldSupport.textOrNull(view.get("view_id"));
            if (viewId != null) {
                selected.add(viewId);
            }
            if (selected.size() == 2) {
                break;
            }
        }
        return selected;
    }

    static List<String> resolveViewIds(ArrayNode views, List<String> requestedViewIds) {
        if (requestedViewIds == null || requestedViewIds.isEmpty()) {
            return defaultViewIds(views);
        }
        Set<String> availableViewIds = new LinkedHashSet<>();
        for (JsonNode view : views) {
            String viewId = JsonFieldSupport.textOrNull(view.get("view_id"));
            if (viewId != null) {
                availableViewIds.add(viewId);
            }
        }
        List<String> selected = new ArrayList<>(requestedViewIds.size());
        for (String viewId : requestedViewIds) {
            if (viewId == null || viewId.isBlank()) {
                throw new ContractViolationException("view_id must not be blank", null);
            }
            if (!availableViewIds.contains(viewId)) {
                throw new ContractViolationException("Unknown view_id", viewId);
            }
            selected.add(viewId);
        }
        return List.copyOf(selected);
    }

    private static String firstViewId(ArrayNode views, String kind, String exactViewIdOrNull) {
        for (JsonNode view : views) {
            if (!kind.equals(JsonFieldSupport.textOrNull(view.get("kind")))) {
                continue;
            }
            String viewId = JsonFieldSupport.textOrNull(view.get("view_id"));
            if (viewId == null) {
                continue;
            }
            if (exactViewIdOrNull == null || exactViewIdOrNull.equals(viewId)) {
                return viewId;
            }
        }
        return null;
    }

    private static String secondViewId(ArrayNode views, String kind) {
        boolean seenFirst = false;
        for (JsonNode view : views) {
            if (!kind.equals(JsonFieldSupport.textOrNull(view.get("kind")))) {
                continue;
            }
            String viewId = JsonFieldSupport.textOrNull(view.get("view_id"));
            if (viewId == null) {
                continue;
            }
            if (!seenFirst) {
                seenFirst = true;
                continue;
            }
            return viewId;
        }
        return null;
    }

    private static ObjectNode viewNode(String viewId, String kind, String title) {
        ObjectNode view = OM.createObjectNode();
        view.put("view_id", viewId);
        view.put("kind", kind);
        view.put("title", title);
        return view;
    }

    private static Double numberFromObject(Object value) {
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            return Double.isFinite(doubleValue) ? doubleValue : null;
        }
        return null;
    }

    private static Map<String, Object> stringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && !key.isBlank()) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    private static double firstFiniteOrDefault(Double a, Double b, double defaultValue) {
        if (a != null && Double.isFinite(a)) {
            return a;
        }
        return defaultValue;
    }

    private ActionMetadataRepository.ActionMetadata resolvedActionMetadata(
            Map<String, ActionMetadataRepository.ActionMetadata> metas,
            String actionId) {
        ActionMetadataRepository.ActionMetadata meta = metas.get(actionId);
        if (meta == null && actionMetadata.isEnabled()) {
            return ActionMetadataRepository.fallback(actionId);
        }
        return meta;
    }

    private void putResolvedActionMetadata(
            ObjectNode node,
            String actionId,
            Map<String, ActionMetadataRepository.ActionMetadata> metas,
            boolean fallbackNameToActionId) {
        ActionMetadataJsonSupport.putActionDisplayMetadata(
                node,
                actionId,
                resolvedActionMetadata(metas, actionId),
                fallbackNameToActionId);
    }

    private static void fillMissingActionMetadata(
            ObjectNode decision,
            String actionId,
            ActionMetadataLookup.ActionMetadata meta) {
        if (JsonFieldSupport.textOrNull(decision.get("action_name")) == null) {
            JsonFieldSupport.putStringOrNull(
                    decision,
                    "action_name",
                    ActionMetadataJsonSupport.actionNameOrNull(actionId, meta, true));
        }
        if (JsonFieldSupport.textOrNull(decision.get("action_image_url")) == null) {
            JsonFieldSupport.putStringOrNull(
                    decision,
                    "action_image_url",
                    ActionMetadataJsonSupport.actionImageUrlOrNull(meta));
        }
    }

    private record ViewDefinition(
            String viewId,
            String kind,
            String title,
            String selectionAlgorithmRuntimeIdOrNull,
            String algorithmRuntimeId,
            String algorithmName,
            String algorithmVersion,
            String parameterId
    ) {
        ObjectNode toJson() {
            ObjectNode view = viewNode(viewId, kind, title);
            JsonFieldSupport.putStringOrNull(view, "algorithm_runtime_id", algorithmRuntimeId);
            JsonFieldSupport.putStringOrNull(view, "algorithm_name", algorithmName);
            JsonFieldSupport.putStringOrNull(view, "algorithm_version", algorithmVersion);
            JsonFieldSupport.putStringOrNull(view, "parameter_id", parameterId);
            return view;
        }
    }

    record ExampleViewData(ArrayNode shownCandidates, ArrayNode views, List<String> defaultViewIds) {
    }

    record OnlineCandidate(String actionId, int originalIndex, Map<String, OnlineMetric> metricsByView) {
    }

    record OnlineMetric(Double rank, Double score, ObjectNode properties) {
    }
}
