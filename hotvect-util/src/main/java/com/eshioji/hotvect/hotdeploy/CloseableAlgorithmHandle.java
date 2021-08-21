package com.eshioji.hotvect.hotdeploy;

import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.function.Supplier;

public class CloseableAlgorithmHandle<R> implements Closeable {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String ALGORITHM_DEFINITION_CONFIG_LOCATION = "algorithm_definition.json";

    private final CloseableJarHandle closeableJarHandle;
    private final AlgorithmMetadata metadata;
    private final ExampleDecoder<R> exampleDecoder;
    private final ExampleEncoder<R> exampleEncoder;
    private final Scorer<R> scorer;

    public CloseableAlgorithmHandle(CloseableJarHandle closeableJarHandle) throws Exception {
        this.closeableJarHandle = closeableJarHandle;
        this.metadata = OM.readValue(readResource(closeableJarHandle.getClassLoader(), ALGORITHM_DEFINITION_CONFIG_LOCATION), AlgorithmMetadata.class);
        this.exampleDecoder = instantiate(metadata.getExampleDecoderFactoryName());
        this.exampleEncoder = instantiate(metadata.getExampleEncoderFactoryName());
        this.scorer = instantiate(metadata.getScorerFactoryName());
    }

    private <T> T instantiate(String factoryName) throws Exception {
        var classloader= closeableJarHandle.getClassLoader();
        Supplier<T> factory = (Supplier<T>) classloader.loadClass(factoryName).getDeclaredConstructor().newInstance();
        return factory.get();
    }

    public AlgorithmMetadata getMetadata() {
        return metadata;
    }

    public ExampleDecoder<R> getExampleDecoder() {
        return exampleDecoder;
    }

    public ExampleEncoder<R> getExampleEncoder() {
        return exampleEncoder;
    }

    public Scorer<R> getScorer() {
        return scorer;
    }

    @Override
    public void close() throws IOException {
        this.closeableJarHandle.close();
    }

    private static String readResource(ClassLoader classLoader, String resourceName) throws IOException {
        try (InputStream is = classLoader.getResourceAsStream(resourceName)) {
            return CharStreams.toString(new InputStreamReader(is, Charsets.UTF_8));
        }
    }

    public static <R> CloseableAlgorithmHandle<R> loadAlgorithm(Path jarPath) throws Exception {
        return new CloseableAlgorithmHandle<>(CloseableJarLoader.load(jarPath));
    }

}
