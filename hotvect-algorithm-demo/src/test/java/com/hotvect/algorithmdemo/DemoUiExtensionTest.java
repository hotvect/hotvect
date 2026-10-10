package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.serve.SelectedRuntime;
import com.hotvect.serve.ServeApplication;
import com.hotvect.utils.AlgorithmDefinitionReader;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoUiExtensionTest {
    private static final ObjectMapper OM = new ObjectMapper();
    @TempDir Path temp;
    private DemoUiExtension extension;
    private MockMvc mvc;
    private ObjectNode originalPayload;
    private ObjectNode originalExample;
    private final List<JsonNode> executedPayloads = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        originalPayload = (ObjectNode) OM.readTree("""
                {"customer_number":"customer-1",
                 "request_features":{"query":{"query_string":"original","language":"en"}},
                 "items":["one","two"]}
                """);
        originalExample = OM.createObjectNode().put("example_id", "example-1");
        originalExample.putObject("features").putObject("shared_features")
                .put("data", originalPayload.toString());
        Path examples = Files.createDirectory(temp.resolve("examples"));
        Files.writeString(examples.resolve("example.json"), originalExample.toString());

        var definitionJson = OM.createObjectNode()
                .put("algorithm_name", "test-ranker")
                .put("algorithm_version", "1.0.0")
                .put("algorithm_factory_classname", "test.UnusedRankerFactory")
                .put("decoder_factory_classname", EmbeddedJsonDecoderFactory.class.getName());
        var definition = new AlgorithmDefinitionReader().parse(definitionJson);
        Ranker<JsonNode, String> ranker = mock(Ranker.class);
        when(ranker.rank(any())).thenAnswer(invocation -> {
            RankingRequest<JsonNode, String> request = invocation.getArgument(0);
            executedPayloads.add(request.shared());
            String query = request.shared().at("/request_features/query/query_string").asText();
            return RankingResponse.newResponse(List.of(RankingDecision.builder(query, 0, query).build()));
        });
        var selected = new SelectedRuntime(
                new AlgorithmInstance<>(definition, null, ranker, new TypeToken<Ranker<JsonNode, String>>() {}),
                AlgorithmRuntimeId.leaf(ParameterizedAlgorithmId.from(definition, null)),
                getClass().getClassLoader());
        ServeApplication app = mock(ServeApplication.class);
        when(app.selectRuntime(nullable(String.class))).thenReturn(selected);
        when(app.buildMetadata()).thenReturn(OM.createObjectNode()
                .put("algorithm_runtime_id", selected.identity().value()));
        when(app.readRequestBodyBytes(any())).thenAnswer(invocation ->
                ((HttpServletRequest) invocation.getArgument(0)).getInputStream().readAllBytes());

        Options options = new Options();
        options.ui = true;
        options.sourcePath = examples.toFile();
        options.demoSqlitePath = temp.resolve("demo.db").toFile();
        extension = new DemoUiExtension(options);
        extension.initialize(app);
        mvc = MockMvcBuilders.standaloneSetup(extension).build();
    }

    @AfterEach
    void tearDown() {
        if (extension != null) {
            extension.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "predict", "compare"})
    void partialVirtualEditPreservesSiblingsAndReplacesArrays(String route) throws Exception {
        ObjectNode patch = OM.createObjectNode();
        ObjectNode virtual = patch.putObject("features").putObject("shared_features").putObject("data__json");
        virtual.putObject("request_features").putObject("query").put("query_string", "changed");
        virtual.putArray("items").add("replacement");
        ObjectNode expected = originalPayload.deepCopy();
        ((ObjectNode) expected.at("/request_features/query")).put("query_string", "changed");
        expected.putArray("items").add("replacement");

        execute(route, OM.createObjectNode().put("example_index", 0).put("override_json", patch.toString()), expected);

        // A subsequent run still sees the unedited stored example.
        executedPayloads.clear();
        execute(route, OM.createObjectNode().put("example_index", 0), originalPayload);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "predict", "compare"})
    void directStringEditIsNotShadowedByVirtualFields(String route) throws Exception {
        ObjectNode replacement = originalPayload.deepCopy();
        ((ObjectNode) replacement.at("/request_features/query")).put("query_string", "raw edit");
        replacement.remove("items");
        ObjectNode patch = OM.createObjectNode();
        patch.putObject("features").putObject("shared_features").put("data", replacement.toString());

        execute(route, OM.createObjectNode().put("example_index", 0).put("override_json", patch.toString()), replacement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "predict", "compare"})
    void fullVirtualPayloadIsCollapsedBeforeExecution(String route) throws Exception {
        ObjectNode replacement = originalPayload.deepCopy();
        ((ObjectNode) replacement.at("/request_features/query")).put("query_string", "full edit");
        ObjectNode example = originalExample.deepCopy();
        ((ObjectNode) example.at("/features/shared_features")).set("data__json", replacement);

        execute(route, OM.createObjectNode().set("example_json", example), replacement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "predict", "compare"})
    void invalidFullVirtualPayloadFailsOnEveryRoute(String route) throws Exception {
        ObjectNode replacement = originalPayload.deepCopy();
        replacement.remove("customer_number");
        ObjectNode example = originalExample.deepCopy();
        ((ObjectNode) example.at("/features/shared_features")).set("data__json", replacement);

        mvc.perform(post("/api/demo/" + route).contentType("application/json")
                        .content(OM.createObjectNode().set("example_json", example).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details").value("Missing customer_number"));
    }

    @Test
    void omittedLimitDefaultsTo100() throws Exception {
        mvc.perform(get("/api/demo/examples"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limit").value(100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100"})
    void acceptsLimitsAtBothBounds(String limit) throws Exception {
        mvc.perform(get("/api/demo/examples").param("limit", limit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limit").value(Integer.parseInt(limit)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "", " ", "1.5", "2147483648"})
    void rejectsMalformedLimits(String limit) throws Exception {
        mvc.perform(get("/api/demo/examples").param("limit", limit))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message").value("limit must be an integer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "500"})
    void rejectsOutOfRangeLimits(String limit) throws Exception {
        mvc.perform(get("/api/demo/examples").param("limit", limit))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message").value("limit must be between 1 and 100"));
    }

    private void execute(String route, ObjectNode body, JsonNode expectedPayload) throws Exception {
        var result = mvc.perform(post("/api/demo/" + route).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andReturn();
        String resultPath = switch (route) {
            case "run" -> "/decisions/0/action_id";
            case "predict" -> "/processed_candidates/0/id";
            case "compare" -> "/runs/0/decisions/0/action_id";
            default -> throw new IllegalArgumentException(route);
        };
        assertEquals(expectedPayload.at("/request_features/query/query_string"),
                OM.readTree(result.getResponse().getContentAsString()).at(resultPath));
        assertEquals(List.of(expectedPayload), executedPayloads);
    }

    public static final class EmbeddedJsonDecoderFactory implements Function<Optional<JsonNode>, ExampleDecoder<?>> {
        @Override
        public ExampleDecoder<?> apply(Optional<JsonNode> ignored) {
            return input -> {
                try {
                    JsonNode example = OM.readTree(input);
                    JsonNode shared = example.at("/features/shared_features");
                    if (shared.has("data__json")) {
                        throw new IllegalArgumentException("Virtual field reached decoder");
                    }
                    JsonNode payload = OM.readTree(shared.get("data").asText());
                    if (!payload.has("customer_number")) {
                        throw new IllegalArgumentException("Missing customer_number");
                    }
                    return List.of(new RankingExample<>("example-1", OfflineRankingRequest.ofAvailableActions(
                            "example-1", payload, List.of(AvailableAction.of("action-1", "action-1"))), List.of()));
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException(e);
                }
            };
        }
    }
}
