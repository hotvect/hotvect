package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.AlgorithmRuntimeIdentity;
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
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.StrictChildFirstClassLoader;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.ZipFiles;
import com.hotvect.utils.AdditionalProperties;
import com.hotvect.utils.AlgorithmDefinitionReader;
import com.hotvect.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipFile;

final class AlgorithmRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AlgorithmRuntime.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final File algorithmJar;
    private final File parameterPath;
    private final AlgorithmDefinition algorithmDefinition;
    private final AlgorithmInstance<?> algorithmInstance;
    private final ExampleDecoder<?> exampleDecoder;
    private final AlgorithmRuntimeIdentity identity;
    private final boolean closeAlgorithmInstance;

    AlgorithmRuntime(File algorithmJar, String algorithmName, File algorithmOverride, File parameterPath) throws Exception {
        this.algorithmJar = algorithmJar;
        this.parameterPath = parameterPath;
        var classLoader = createAlgorithmClassLoader(algorithmJar);
        var algorithmInstanceFactory = new AlgorithmInstanceFactory(
                classLoader,
                ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE),
                false
        );
        AlgorithmDefinition baseDefinition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(algorithmName, classLoader);
        this.algorithmDefinition = applyAlgorithmOverride(baseDefinition, algorithmOverride);

        this.algorithmInstance = algorithmInstanceFactory.load(this.algorithmDefinition, parameterPath, Map.of());
        this.exampleDecoder = loadExampleDecoder(classLoader, this.algorithmDefinition);
        this.identity = AlgorithmRuntimeIdentity.from(
                this.algorithmDefinition,
                this.algorithmInstance.algorithmParameterMetadata());
        this.closeAlgorithmInstance = true;
    }

    AlgorithmRuntime(AlgorithmInstance<?> algorithmInstance) throws Exception {
        this.algorithmJar = null;
        this.parameterPath = null;
        this.algorithmInstance = Objects.requireNonNull(algorithmInstance, "algorithmInstance must not be null");
        this.algorithmDefinition = algorithmInstance.algorithmDefinition();
        this.exampleDecoder = loadExampleDecoder(
                algorithmInstance.algorithm().getClass().getClassLoader(),
                this.algorithmDefinition);
        this.identity = AlgorithmRuntimeIdentity.from(
                this.algorithmDefinition,
                this.algorithmInstance.algorithmParameterMetadata());
        this.closeAlgorithmInstance = false;
    }

    AlgorithmDefinition getAlgorithmDefinition() {
        return algorithmDefinition;
    }

    AlgorithmParameterMetadata getAlgorithmParameterMetadataOrNull() {
        return algorithmInstance.algorithmParameterMetadata();
    }

    JsonNode getAlgorithmParameterMetadataJson() throws IOException {
        if (parameterPath != null) {
            return readAlgorithmParameterMetadataJson(algorithmDefinition, parameterPath);
        }
        AlgorithmParameterMetadata metadata = algorithmInstance.algorithmParameterMetadata();
        return metadata == null ? OM.getNodeFactory().nullNode() : parameterMetadataJson(metadata);
    }

    AlgorithmRuntimeIdentity identity() {
        return identity;
    }

    String algorithmJarPathOrNull() {
        return algorithmJar == null ? null : algorithmJar.getAbsolutePath();
    }

    String parameterPathOrNull() {
        return parameterPath == null ? null : parameterPath.getAbsolutePath();
    }

    String getHotvectVersionFromMavenOrNull() {
        if (algorithmJar == null || !algorithmJar.exists() || !algorithmJar.isFile()) {
            return null;
        }
        SortedSet<String> versions = new TreeSet<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(algorithmJar)) {
            addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-core", versions);
            addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-tensorflow", versions);
            addMavenPomPropertiesVersionIfPresent(zf, "com.hotvect", "hotvect-catboost", versions);
        } catch (Exception ignored) {
            return null;
        }
        if (versions.isEmpty()) {
            return null;
        }
        return String.join(", ", versions);
    }

    private static JsonNode readAlgorithmParameterMetadataJson(
            AlgorithmDefinition algorithmDefinition,
            File parameterPath) throws IOException {
        String entryName = algorithmDefinition.algorithmId().algorithmName() + "/algorithm-parameters.json";
        try (ZipFile zipFile = new ZipFile(parameterPath);
             InputStream input = ZipFiles.readFromZipByName(zipFile, entryName)) {
            return OM.readTree(input);
        }
    }

    private static ObjectNode parameterMetadataJson(AlgorithmParameterMetadata metadata) {
        ObjectNode node = OM.createObjectNode();
        node.put("algorithm_name", metadata.algorithmId().algorithmName());
        node.put("algorithm_version", metadata.algorithmId().algorithmVersion());
        node.put("parameter_id", metadata.parameterId());
        node.put("ran_at", metadata.ranAt().toString());
        metadata.lastTestTime().ifPresentOrElse(
                lastTestTime -> node.put("last_test_time", lastTestTime.toString()),
                () -> node.putNull("last_test_time"));
        return node;
    }

    private static void addMavenPomPropertiesVersionIfPresent(java.util.zip.ZipFile zf, String groupId, String artifactId, Set<String> versions)
            throws IOException {
        String entry = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        var ze = zf.getEntry(entry);
        if (ze == null) {
            return;
        }
        try (var in = zf.getInputStream(ze)) {
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            String v = props.getProperty("version");
            if (v != null && !v.isBlank()) {
                versions.add(v.trim());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ExampleDecoder<?> loadExampleDecoder(
            ClassLoader classLoader,
            AlgorithmDefinition algorithmDefinition) throws Exception {
        if (algorithmDefinition.decoderFactoryName() == null) {
            throw new ContractViolationException("Algorithm definition missing decoder_factory_classname", null);
        }
        Object decoderFactory = classLoader.loadClass(algorithmDefinition.decoderFactoryName())
                .getDeclaredConstructor()
                .newInstance();
        if (decoderFactory instanceof ExampleDecoderFactory<?> factory) {
            return factory.create(algorithmDefinition.testDecoderParameter());
        }
        return ((Function<Optional<JsonNode>, ExampleDecoder<?>>) decoderFactory)
                .apply(algorithmDefinition.testDecoderParameter());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    ObjectNode runExample(ObjectNode caseNode, String expectedExampleIdOrNull, ActionMetadataLookup metadataLookup) throws Exception {
        String jsonString = OM.writeValueAsString(caseNode);
        List<?> decoded = decodeSingleExample(jsonString, expectedExampleIdOrNull);

        Example<OfflineRequest<?>, ?> ex = (Example<OfflineRequest<?>, ?>) decoded.getFirst();
        String decodedExampleId = requireNonEmptyExampleId(ex.exampleId());
        if (expectedExampleIdOrNull != null && !Objects.equals(decodedExampleId, expectedExampleIdOrNull)) {
            throw new ContractViolationException(
                    "Decoded example_id does not match case JSON example_id",
                    "case=" + expectedExampleIdOrNull + ", decoded=" + decodedExampleId
            );
        }

        Object algo = algorithmInstance.algorithm();

        if (algo instanceof Ranker<?, ?>) {
            return runRanker(decodedExampleId, (Ranker) algo, (RankingRequest) ex.request(), metadataLookup);
        }

        if (algo instanceof TopK<?, ?>) {
            return runTopK(decodedExampleId, (TopK) algo, (TopKRequest) ex.request(), metadataLookup);
        }

        throw new ContractViolationException("Unsupported algorithm type: " + algo.getClass().getCanonicalName(), null);
    }

    @SuppressWarnings("unchecked")
    String tryDecodeExampleIdOrNull(String caseJsonString) {
        try {
            List<?> decoded = exampleDecoder.apply(caseJsonString);
            if (decoded == null || decoded.isEmpty()) {
                return null;
            }
            if (decoded.size() != 1) {
                return null;
            }
            Example<OfflineRequest<?>, ?> ex = (Example<OfflineRequest<?>, ?>) decoded.getFirst();
            String exampleId = ex.exampleId();
            if (exampleId == null || exampleId.isBlank()) {
                return null;
            }
            return exampleId;
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    List<DecodedOnlineCandidate> decodeOnlineCandidates(String caseJsonString) {
        List<?> decoded = decodeSingleExample(caseJsonString, null);
        Example<OfflineRequest<?>, Outcome<?, ?>> ex = (Example<OfflineRequest<?>, Outcome<?, ?>>) decoded.getFirst();
        List<Outcome<?, ?>> outcomes = (List<Outcome<?, ?>>) ex.outcomes();
        if (outcomes == null || outcomes.isEmpty()) {
            return List.of();
        }
        List<DecodedOnlineCandidate> out = new ArrayList<>(outcomes.size());
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome<?, ?> outcome = outcomes.get(i);
            Decision<?> decision = outcome.decision();
            String actionId = actionIdOrNull(decision);
            if (actionId == null || actionId.isBlank()) {
                throw new ContractViolationException("Decoded online outcome is missing action_id", "outcome_index=" + i);
            }
            Map<String, Object> outcomeProperties = AdditionalProperties.getAdditionalProperties(outcome.outcome());
            Map<String, Object> onlineProperties = stringKeyMap(outcomeProperties.get("online"));
            if (!onlineProperties.isEmpty()) {
                out.add(new DecodedOnlineCandidate(actionId, decision.score(), onlineProperties, i));
            }
        }
        return out;
    }

    ObjectNode runRawExampleJson(ObjectNode exampleNode, ActionMetadataLookup metadataLookup) throws Exception {
        String expectedExampleId = JsonFieldSupport.nonEmptyStringField(exampleNode, "example_id").orElse(null);
        JsonInStringSupport.collapseVirtualJsonFields(exampleNode);
        return runExample(exampleNode, expectedExampleId, metadataLookup);
    }

    private ObjectNode runRanker(
            String exampleId,
            Ranker ranker,
            RankingRequest request,
            ActionMetadataLookup metadataLookup
    ) {
        RankingResponse<?> response = ranker.rank(request);
        List<RankingDecision<?>> decisions = (List<RankingDecision<?>>) response.decisions();
        List<String> actionIds = decisions.stream().map(d -> requireNonEmptyActionId(d.actionId())).distinct().toList();
        Map<String, ActionMetadataLookup.ActionMetadata> metaById = metadataLookup.getAllIfEnabled(actionIds);
        Map<String, Map<String, Object>> requestActionPropertiesById = actionPropertiesById(request.actions());

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
            ActionMetadataLookup.ActionMetadata meta = actionMetadataFromProperties(
                    actionId,
                    additionalProperties,
                    metaById.get(actionId));

            ObjectNode d = OM.createObjectNode();
            populateDecisionNode(
                    d,
                    rank,
                    actionId,
                    decision.score(),
                    decision.probability(),
                    additionalProperties,
                    meta);
            decisionsNode.add(d);
        }

        return root;
    }

    private ObjectNode runTopK(
            String exampleId,
            TopK topK,
            TopKRequest request,
            ActionMetadataLookup metadataLookup
    ) {
        TopKResponse<?> response = topK.apply(request);
        List<TopKDecision<?>> decisions = (List<TopKDecision<?>>) response.decisions();
        List<String> actionIds = decisions.stream().map(d -> requireNonEmptyActionId(d.actionId())).distinct().toList();
        Map<String, ActionMetadataLookup.ActionMetadata> metaById = metadataLookup.getAllIfEnabled(actionIds);

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
            ActionMetadataLookup.ActionMetadata meta = actionMetadataFromProperties(
                    actionId,
                    additionalProperties,
                    metaById.get(actionId));

            ObjectNode d = OM.createObjectNode();
            populateDecisionNode(
                    d,
                    rank,
                    actionId,
                    decision.score(),
                    decision.probability(),
                    additionalProperties,
                    meta);
            decisionsNode.add(d);
        }

        return root;
    }

    private static String requireNonEmptyActionId(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            throw new ContractViolationException("Algorithm response is missing required action_id", null);
        }
        return actionId;
    }

    private static void populateDecisionNode(
            ObjectNode decisionNode,
            int rank,
            String actionId,
            Double score,
            Double probability,
            Map<String, Object> additionalProperties,
            ActionMetadataLookup.ActionMetadata meta) {
        decisionNode.put("rank", rank);
        decisionNode.put("action_id", actionId);
        ActionMetadataJsonSupport.putActionDisplayMetadata(decisionNode, actionId, meta);
        if (score != null) {
            decisionNode.put("score", score);
        }
        if (probability != null) {
            decisionNode.put("probability", probability);
        }
        decisionNode.set("additional_properties", OM.valueToTree(additionalProperties));
    }

    private static Map<String, Map<String, Object>> actionPropertiesById(List<? extends AvailableAction<?>> actions) {
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
        } catch (ReflectiveOperationException e) {
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
        if (properties == null) {
            return null;
        }
        Object value = properties.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private List<?> decodeSingleExample(String caseJsonString, String expectedExampleIdOrNull) {
        List<?> decoded;
        try {
            decoded = exampleDecoder.apply(caseJsonString);
        } catch (RuntimeException e) {
            throw new ContractViolationException("Failed to decode request payload", e.getMessage());
        }
        if (decoded == null || decoded.isEmpty()) {
            throw new ContractViolationException(
                    "Decoder produced no examples for case",
                    expectedExampleIdOrNull == null ? null : "expected example_id=" + expectedExampleIdOrNull
            );
        }
        if (decoded.size() != 1) {
            throw new ContractViolationException("Decoder must produce exactly 1 example for a case; got " + decoded.size(), null);
        }
        return decoded;
    }

    private static String actionIdOrNull(Decision<?> decision) {
        if (decision == null) {
            return null;
        }
        String actionId = decision.actionId();
        if (actionId != null && !actionId.isBlank()) {
            return actionId;
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

    private static String requireNonEmptyExampleId(String exampleId) {
        if (exampleId == null || exampleId.isBlank()) {
            throw new ContractViolationException("Decoded example_id is missing/blank", null);
        }
        return exampleId;
    }

    private static AlgorithmDefinition applyAlgorithmOverride(AlgorithmDefinition baseDefinition, File overrideFile) throws IOException {
        if (overrideFile == null) {
            return baseDefinition;
        }
        JsonNode overrideNode = OM.readTree(overrideFile);
        if (!overrideNode.isObject()) {
            throw new ContractViolationException("--algorithm-override must be a JSON object", overrideFile.getAbsolutePath());
        }
        JsonNode merged = baseDefinition.rawAlgorithmDefinition().deepCopy();
        JsonUtils.deepMergeJsonNodeWithArrayReplacement(merged, overrideNode);
        try {
            return new AlgorithmDefinitionReader().parse(merged);
        } catch (IOException e) {
            throw new IOException("Failed to parse merged algorithm definition after applying --algorithm-override", e);
        }
    }

    private static ClassLoader createAlgorithmClassLoader(File algorithmJar) throws IOException {
        var jarUrl = algorithmJar.toURI().toURL();
        Set<String> required = Set.of("com.hotvect.core.transform.Namespaces");
        return new StrictChildFirstClassLoader(new java.net.URL[]{jarUrl}, Thread.currentThread().getContextClassLoader(), required);
    }

    @Override
    public void close() {
        if (!closeAlgorithmInstance) {
            return;
        }
        try {
            algorithmInstance.close();
        } catch (Exception e) {
            log.warn("Failed to close algorithm instance", e);
        }
    }

}
