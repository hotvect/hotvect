package com.eshioji.hotvect.hotdeploy;

import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.scoring.Scorer;

import java.nio.file.Path;
import java.util.function.Supplier;

public class AlgorithmDefinition<R> extends CloseableClassDefinition {
    private final AlgorithmMetadata metadata;
    private final ExampleDecoder<R> exampleDecoder;
    private final ExampleEncoder<R> exampleEncoder;
    private final Scorer<R> scorer;

    public AlgorithmDefinition(Path jarFile,
                               ChildFirstCloseableClassloader classLoader,
                               AlgorithmMetadata algorithmMetadata) throws Exception {
        super(jarFile, classLoader);
        this.metadata = algorithmMetadata;
        this.exampleDecoder = instantiate(classLoader, metadata.getExampleDecoderFactoryName());
        this.exampleEncoder = instantiate(classLoader, metadata.getExampleEncoderFactoryName());
        this.scorer = instantiate(classLoader, metadata.getScorerFactoryName());
    }

    private <T> T instantiate(ChildFirstCloseableClassloader classLoader, String factoryName) throws Exception {
        Supplier<T> factory = (Supplier<T>) classLoader.loadClass(factoryName).getDeclaredConstructor().newInstance();
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
}
