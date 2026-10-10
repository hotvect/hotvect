package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.experimentmanagement.algodownload.ArtifactUriAlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.httpclient.FileExperimentManagementStateSource;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.onlineutils.serving.EmsPredictionRuntime;
import com.hotvect.onlineutils.serving.FixedCompositionRuntime;
import com.hotvect.utils.AlgorithmDefinitionReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PredictionWithoutRewardsTest {
    enum Source { DIRECT, FIXED, EMS }

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(Source.class)
    void predictsUnlabelledExamplesWithoutARewardFactory(Source source) throws Exception {
        try (OfflineTaskContext context = context(source, false)) {
            Map<String, Object> result = new PredictTask<>(context).perform();
            assertEquals(1L, result.get("lines_written"));
            try (var files = Files.list(context.options().destinationFile.toPath())) {
                List<Path> shards = files.filter(path -> path.toString().endsWith(".jsonl")).toList();
                assertEquals(1, shards.size());
                JsonNode output = new ObjectMapper().readTree(Files.readString(shards.getFirst()));
                JsonNode decision = output.path("result").get(0);
                assertEquals("item", decision.path("action_id").asText());
                assertEquals(0.75, decision.path("score").asDouble());
                assertFalse(decision.has("reward"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void rejectsLabelledExamplesWithoutARewardFactory(Source source) throws Exception {
        try (OfflineTaskContext context = context(source, true)) {
            Exception failure = assertThrows(Exception.class, () -> new PredictTask<>(context).perform());
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertInstanceOf(IllegalArgumentException.class, cause);
            assertTrue(cause.getMessage().contains("example"));
            assertTrue(cause.getMessage().contains("reward_function_factory_classname is not configured"));
        }
    }

    @Test
    void rewardDependentTrainingSetupStillRejectsMissingFactory() throws Exception {
        try (var factory = new AlgorithmOfflineSupporterFactory(getClass().getClassLoader())) {
            var failure = assertThrows(MalformedAlgorithmException.class, () -> factory.getRewardFunction(definition()));
            assertTrue(failure.getMessage().contains("requires reward_function_factory_classname"));
        }
    }

    private AlgorithmDefinition definition() throws Exception {
        return new AlgorithmDefinitionReader().parse("""
                {"algorithm_name":"policy", "algorithm_version":"1",
                 "algorithm_factory_classname":"%s", "decoder_factory_classname":"%s"}
                """.formatted(PolicyFactory.class.getName(), DecoderFactory.class.getName()));
    }

    private OfflineTaskContext context(Source source, boolean labelled) throws Exception {
        AlgorithmDefinition definition = definition();
        Path jar = tempDir.resolve("policy.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("policy-algorithm-definition.json"));
            output.write(definition.rawAlgorithmDefinition().toString().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path input = tempDir.resolve("input.jsonl");
        Files.writeString(input, "{\"customer_id\":\"customer\",\"labelled\":" + labelled + "}\n");
        Options options = new Options();
        options.sourceFiles = Map.of("default", List.of(input.toFile()));
        options.destinationFile = tempDir.resolve("prediction").toFile();
        options.maxThreads = 1;
        options.batchSize = 1;
        options.ordered = true;
        var metrics = new SimpleMeterRegistry();
        var execution = ExecutionContext.batch(InputSemantic.OFFLINE);
        return switch (source) {
            case DIRECT -> {
                options.algorithmSource = new OfflineAlgorithmSource.Direct(
                        jar.toFile(), "policy", List.of(), List.of(), null);
                yield new OfflineTaskContext(
                        new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()),
                        metrics, options, definition);
            }
            case FIXED -> {
                Path composition = tempDir.resolve("composition.json");
                Files.writeString(composition, """
                        {"root":"policy@1", "algorithms":{"policy@1":{"jar_uri":"%s"}}, "slot_bindings":{}}
                        """.formatted(jar.toUri()));
                var runtime = FixedCompositionRuntime.builder().composition(composition)
                        .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                        .algorithmParentClassLoader(getClass().getClassLoader())
                        .executionContext(execution).build();
                yield OfflineTaskContext.forFixedComposition(metrics, options, runtime.rootDefinition(), runtime, null);
            }
            case EMS -> {
                Path snapshot = tempDir.resolve("snapshot.json");
                Files.writeString(snapshot, """
                        {"slots":{"policy-slot":{"slot_salt":"salt", "total_number_of_shards":1,
                          "default_variant":{"variant_id":1, "created_at":"2026-09-07T00:00:00Z",
                            "is_default":true, "is_control":false, "shard_allocation_ratio":100,
                            "algorithm":{"algorithm_name":"policy", "algorithm_version":"1",
                              "absolute_s3_algorithm_jar_path":"%s"}},
                          "experiments":[], "user_forced_assignments":[]}}}
                        """.formatted(jar.toUri()));
                var runtime = EmsPredictionRuntime.builder().rootSlot("policy-slot")
                        .stateSource(new FileExperimentManagementStateSource(snapshot))
                        .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                        .algorithmParentClassLoader(getClass().getClassLoader())
                        .executionContext(execution).build();
                yield OfflineTaskContext.forEmsPrediction(metrics, options, runtime.rootDefinitions().getFirst(),
                        runtime, null, snapshot.toUri().toString(), "policy-slot", "/customer_id");
            }
        };
    }

    public static final class PolicyFactory implements SimpleRankerFactory<String, String> {
        @Override
        public Ranker<String, String> apply(Optional<JsonNode> configuration) {
            return request -> RankingResponse.newResponse(
                    List.of(RankingDecision.builder("item", 0, "Item").withScore(0.75).build()));
        }
    }

    public static final class DecoderFactory implements RankingExampleDecoderFactory<String, String, Double> {
        @Override
        public RankingExampleDecoder<String, String, Double> apply(Optional<JsonNode> configuration) {
            return input -> List.of(new RankingExample<>("example",
                    OfflineRankingRequest.ofAvailableActions("example", "shared", List.of(AvailableAction.of("item", "Item"))),
                    input.contains("\"labelled\":true")
                            ? List.of(new RankingOutcome<>(RankingDecision.builder("item", 0, "Item").build(), 1.0))
                            : List.of()));
        }
    }
}
