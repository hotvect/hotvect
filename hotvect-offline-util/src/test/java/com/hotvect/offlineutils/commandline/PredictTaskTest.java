package com.hotvect.offlineutils.commandline;

import com.codahale.metrics.MetricRegistry;
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
import com.hotvect.offlineutils.util.OrderedFileMapper;
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
import static org.mockito.Mockito.*;


public class PredictTaskTest {
    public static class ExampleDecoderFactory implements RankingExampleDecoderFactory<String, String, String> {
        @Override
        public RankingExampleDecoder<String, String, String> apply(Optional<JsonNode> hyperparameter) {
            return toDecode -> ImmutableList.of(
                    new RankingExample<>(
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

    @Test
    void noSampling() throws Exception {
        AlgorithmDefinition mockedAlgoDef = mock(AlgorithmDefinition.class);
        when(mockedAlgoDef.getDecoderFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + ExampleDecoderFactory.class.getSimpleName());
        when(mockedAlgoDef.getTransformerFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + TestTransformer.class.getSimpleName());
        when(mockedAlgoDef.getRewardFunctionFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + TestRewardFunctionFactoroy.class.getSimpleName());
        when(mockedAlgoDef.getAlgorithmFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + TestAlgorithmFactory.class.getSimpleName());
        when(mockedAlgoDef.getAlgorithmId()).thenReturn(new AlgorithmId("test-algorithm", "1.2.3"));

        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        File tempFile = File.createTempFile("temp", ".txt");
        tempFile.deleteOnExit();
        options.destinationFile = tempFile;

        try{
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new MetricRegistry(), options, mockedAlgoDef);

            PredictTask<Example, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            var result = testSubject.perform();
            System.out.println(result);

        }finally {
            tempFile.delete();
        }
    }

    @Test
    void withSampling() throws Exception {
        AlgorithmDefinition mockedAlgoDef = mock(AlgorithmDefinition.class);
        when(mockedAlgoDef.getDecoderFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + ExampleDecoderFactory.class.getSimpleName());
        when(mockedAlgoDef.getTransformerFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + TestTransformer.class.getSimpleName());
        when(mockedAlgoDef.getRewardFunctionFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + TestRewardFunctionFactoroy.class.getSimpleName());
        when(mockedAlgoDef.getAlgorithmFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() +"$" + TestAlgorithmFactory.class.getSimpleName());
        when(mockedAlgoDef.getAlgorithmId()).thenReturn(new AlgorithmId("test-algorithm", "1.2.3"));

        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        File tempFile = File.createTempFile("temp", ".txt");
        tempFile.deleteOnExit();
        options.destinationFile = tempFile;
        options.samples = 1987;

        try{
            OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new MetricRegistry(), options, mockedAlgoDef);

            PredictTask<Example, Ranker<String, String>, String> testSubject = new PredictTask<>(offlineTaskContext);

            var result = testSubject.perform();
            System.out.println(result);

        }finally {
            tempFile.delete();
        }

    }



    @Test
    void shouldThrowExceptionWhenNoRowsWritten() throws Exception {
        AlgorithmDefinition mockedAlgoDef = mock(AlgorithmDefinition.class);
        when(mockedAlgoDef.getDecoderFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() + "$" + ExampleDecoderFactory.class.getSimpleName());
        when(mockedAlgoDef.getTransformerFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() + "$" + TestTransformer.class.getSimpleName());
        when(mockedAlgoDef.getRewardFunctionFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() + "$" + TestRewardFunctionFactoroy.class.getSimpleName());
        when(mockedAlgoDef.getAlgorithmFactoryName()).thenReturn(ExampleDecoderFactory.class.getDeclaringClass().getCanonicalName() + "$" + TestAlgorithmFactory.class.getSimpleName());
        when(mockedAlgoDef.getAlgorithmId()).thenReturn(new AlgorithmId("test-algorithm", "1.2.3"));

        Options options = new Options();
        options.parameters = Paths.get(Objects.requireNonNull(this.getClass().getResource("test-algorithm-parameter.zip")).toURI()).toFile();
        options.sourceFiles = ImmutableMap.of("default", ImmutableList.of(Paths.get(Objects.requireNonNull(this.getClass().getResource("multiple")).toURI()).toFile()));
        File tempFile = File.createTempFile("temp", ".txt");
        tempFile.deleteOnExit();
        options.destinationFile = tempFile;

        OfflineTaskContext offlineTaskContext = new OfflineTaskContext(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), new MetricRegistry(), options, mockedAlgoDef);

        PredictTask<Example, Ranker<String, String>, String> testSubject = spy(new PredictTask<>(offlineTaskContext));

        doReturn(Map.of("total_record_count", 0L)).when(testSubject)
                .callOrderedFileMapper(any(OrderedFileMapper.class));

        // Verify that the perform method throws an exception
        Exception exception = assertThrows(Exception.class, testSubject::perform);
        assertEquals("No rows have been written.", exception.getMessage());
    }
}