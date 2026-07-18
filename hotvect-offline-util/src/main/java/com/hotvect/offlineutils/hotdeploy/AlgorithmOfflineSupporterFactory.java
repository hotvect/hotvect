package com.hotvect.offlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.ExampleDecoderFactory;
import com.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.api.execution.WorkloadMode;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class AlgorithmOfflineSupporterFactory extends AlgorithmInstanceFactory implements AlgorithmOfflineInstantiator {
    private static final ExecutionContext EXECUTION_CONTEXT = ExecutionContext.of(WorkloadMode.BATCH, InputSemantic.OFFLINE);

    public AlgorithmOfflineSupporterFactory(File algorithmJar, ClassLoader parent) throws MalformedAlgorithmException {
        super(algorithmJar, parent, EXECUTION_CONTEXT, false);
    }

    public AlgorithmOfflineSupporterFactory(File algorithmJar) throws MalformedAlgorithmException {
        super(algorithmJar, EXECUTION_CONTEXT, false);
    }

    public AlgorithmOfflineSupporterFactory(ClassLoader parent) throws MalformedAlgorithmException {
        super(parent, EXECUTION_CONTEXT, false);
    }

    public <OUTCOME> RewardFunction<OUTCOME> getRewardFunction(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.rewardFunctionFactoryName();
        RewardFunctionFactory<OUTCOME> rewardFunctionFactory = instantiate(factoryName);
        return rewardFunctionFactory.get();
    }

    public <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleDecoder<EXAMPLE> getTrainDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.decoderFactoryName();
        Optional<JsonNode> parameter = algorithmDefinition.trainDecoderParameter();
        ExampleDecoderFactory<EXAMPLE> decoderFactory = instantiate(factoryName);
        return decoderFactory.create(parameter);
    }

    @Override
    public <DEPENDENCY> DEPENDENCY loadFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile, Map<String, AlgorithmInstance<?>> dependencyOverrides) throws MalformedAlgorithmException {
        return super.loadFeatureExtractionDependency(algorithmDefinition, parameterFile, dependencyOverrides);
    }

    public <E> E getFeatureExtractionDependency(AlgorithmDefinition algorithmDefinition, File parameterFile) throws MalformedAlgorithmException {
        return loadFeatureExtractionDependency(algorithmDefinition, parameterFile, Map.of());
    }

    public <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleEncoder<EXAMPLE> getTrainEncoder(AlgorithmDefinition algorithmDefinition, File parameters) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.encoderFactoryName();
        RewardFunction<?> rewardFunction = this.getRewardFunction(algorithmDefinition);

        Object dependency = loadFeatureExtractionDependency(algorithmDefinition, parameters, Map.of());
        ExampleEncoderFactory encoderFactory = instantiate(factoryName);
        Object rawEncoder = encoderFactory.create(dependency, rewardFunction);

        return adaptTrainEncoder(rawEncoder);
    }

    public <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleDecoder<EXAMPLE> getTestDecoder(AlgorithmDefinition algorithmDefinition) throws MalformedAlgorithmException {
        String factoryName = algorithmDefinition.decoderFactoryName();
        Optional<JsonNode> hyperparameter = algorithmDefinition.testDecoderParameter();
        ExampleDecoderFactory<EXAMPLE> factory = instantiate(factoryName);
        return factory.create(hyperparameter);
    }

    @SuppressWarnings("unchecked")
    static <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleEncoder<EXAMPLE> adaptTrainEncoder(Object rawEncoder) {
        if (!(rawEncoder instanceof ExampleEncoder<?> encoder)) {
            throw new IllegalStateException(
                    "Encoder " + rawEncoder.getClass().getName() + " must implement ExampleEncoder."
            );
        }
        if (hasEncodedFileExtension(encoder)) {
            return (ExampleEncoder<EXAMPLE>) encoder;
        }
        return wrapLegacyTrainEncoder(encoder);
    }

    static boolean hasEncodedFileExtension(ExampleEncoder<?> encoder) {
        try {
            String extension = encoder.encodedFileExtension();
            return extension != null && !extension.isEmpty();
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static <EXAMPLE extends Example<? extends OfflineRequest, ?>> ExampleEncoder<EXAMPLE> wrapLegacyTrainEncoder(ExampleEncoder<?> rawEncoder) {
        Function<EXAMPLE, Object> encodingFunction = (Function<EXAMPLE, Object>) (Function<?, ?>) rawEncoder;
        ExampleEncoder<EXAMPLE> currentEncoder = (ExampleEncoder<EXAMPLE>) rawEncoder;

        return new ExampleEncoder<>() {
            @Override
            public ByteBuffer apply(EXAMPLE example) {
                return normalizeEncodedRecord(encodingFunction.apply(example), rawEncoder);
            }

            @Override
            public Optional<String> schemaDescription() {
                return currentEncoder.schemaDescription();
            }

            @Override
            public String encodedFileExtension() {
                return currentEncoder.encodedFileExtension();
            }
        };
    }

    static ByteBuffer normalizeEncodedRecord(Object encodedRecord, Object rawEncoder) {
        if (encodedRecord instanceof String encodedString) {
            return ByteBuffer.wrap((encodedString + "\n").getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalStateException(
                "Unsupported legacy encoded record type from encoder "
                        + rawEncoder.getClass().getName()
                        + ": "
                        + (encodedRecord == null ? "null" : encodedRecord.getClass().getName())
                        + ". Expected java.lang.String."
        );
    }
}
