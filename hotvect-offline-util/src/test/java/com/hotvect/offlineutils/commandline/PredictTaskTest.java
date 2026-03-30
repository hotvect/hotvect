package com.hotvect.offlineutils.commandline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.ranking.*;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class PredictTaskTest {
    public static class ExampleDecoderFactory implements RankingExampleDecoderFactory<String, String, String> {
        @Override
        public RankingExampleDecoder<String, String, String> apply(Optional<JsonNode> hyperparameter) {
            return toDecode -> ImmutableList.of(
                    new RankingExample<String, String, String>(
                            "example",
                            new RankingRequest<>(
                                    "example",
                                    "shared",
                                    ImmutableList.of("action1", "action2")
                            ),
                            ImmutableList.of(
                                    new RankingOutcome<>(
                                            RankingDecision.builder(0, "action1").build(),
                                            "outcome"
                                    )
                            )
                    )
            );
        }
    }

    public static class TestTransformer implements RankingTransformerFactory<String, String> {
        @Override
        public RankingTransformer<String, String> apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameter) {
            return null;
        }
    }

    public static class TestAlgorithmFactory implements RankerFactory<RankingTransformer<String, String>, String, String> {
        @Override
        public Ranker<String, String> apply(RankingTransformer<String, String> stringStringStringRankingTransformer, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
            return rankingRequest -> RankingResponse.newResponse(
                    ImmutableList.of(RankingDecision.builder(0, "action1").build())
            );
        }
    }

    public static class TestRewardFunctionFactoroy implements RewardFunctionFactory<String> {
        @Override
        public RewardFunction<String> get() {
            return s -> 1.0;
        }
    }

    private static AlgorithmDefinition algorithmDefinition() {
        String nestedClassPrefix = ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() + "$";
        return new AlgorithmDefinition(
                null,
                new AlgorithmId("test-algorithm", "1.2.3"),
                ImmutableMap.of(),
                ImmutableMap.of(),
                null,
                nestedClassPrefix + ExampleDecoderFactory.class.getSimpleName(),
                nestedClassPrefix + TestTransformer.class.getSimpleName(),
                null,
                nestedClassPrefix + TestRewardFunctionFactoroy.class.getSimpleName(),
                null,
                nestedClassPrefix + TestAlgorithmFactory.class.getSimpleName(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty(),
                Optional.<JsonNode>empty()
        );
    }

    @Test
    void noSampling() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        File tempFile = File.createTempFile("temp", ".txt");
        tempFile.deleteOnExit();
        options.destinationFile = tempFile;

        try{
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            var result = testSubject.perform();
            System.out.println(result);

        }finally {
            tempFile.delete();
        }
    }

    @Test
    void withSampling() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        File tempFile = File.createTempFile("temp", ".txt");
        tempFile.deleteOnExit();
        options.destinationFile = tempFile;
        options.samples = 1987;

        try{
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

            PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            var result = testSubject.perform();
            System.out.println(result);

        }finally {
            tempFile.delete();
        }

    }



    @Test
    void shouldThrowExceptionWhenNoRowsWritten() throws Exception {
        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        File tempFile = File.createTempFile("temp", ".txt");
        tempFile.deleteOnExit();
        options.destinationFile = tempFile;

        OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new SimpleMeterRegistry(), options, algorithmDefinition());

        PredictTask<? extends Example<?, ?>, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext) {
            @Override
            protected Map<String, Object> callOrderedFileMapper(OrderedFileMapper processor) {
                return Map.of("total_record_count", 0L);
            }
        };

        Exception exception = assertThrows(Exception.class, testSubject::perform);
        assertEquals("No rows have been written.", exception.getMessage());
    }
}
