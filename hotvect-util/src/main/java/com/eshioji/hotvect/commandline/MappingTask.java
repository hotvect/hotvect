package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.*;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.eshioji.hotvect.util.VerboseCallable;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public abstract class MappingTask<R> extends Task {
    protected final AlgorithmDefinition algorithmDefinition;

    public MappingTask(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition) throws Exception {
        super(opts, metricRegistry);
        this.algorithmDefinition = algorithmDefinition;
    }

    protected abstract Map<String, String> perform() throws Exception;

    @Override
    protected Map<String, String> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {}", this.getClass().getSimpleName(), opts.sourceFile, opts.destinationFile);
        Map<String, String> metadata = perform();
        metadata.put("task_type", this.getClass().getSimpleName());
        metadata.put("metadata_location", opts.metadataLocation.toString());
        metadata.put("destination_file", opts.destinationFile.toString());
        metadata.put("source_file", opts.sourceFile.toString());
        metadata.put("algorithm_name", algorithmDefinition.getAlgorithmName());
        metadata.put("algorithm_definition", algorithmDefinition.toString());
        return metadata;
    }

    private Vectorizer<R> getVectorizer() throws Exception {
        String factoryName = this.algorithmDefinition.getVectorizerFactoryName();
        Optional<JsonNode> parameter = this.algorithmDefinition.getVectorizerParameter();
        return ((VectorizerFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(parameter);
    }

    protected ExampleDecoder<R> getTrainDecoder() throws Exception {
        String factoryName = this.algorithmDefinition.getDecoderFactoryName();
        Optional<JsonNode> parameter = this.algorithmDefinition.getTrainDecoderParameter();
        return ((ExampleDecoderFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(parameter);
    }

    protected ExampleEncoder<R> getTrainEncoder() throws Exception {
        String factoryName = this.algorithmDefinition.getEncoderFactoryName();
        Optional<JsonNode> parameter = this.algorithmDefinition.getTrainDecoderParameter();
        return ((ExampleEncoderFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(getVectorizer(), parameter);
    }

    protected ExampleDecoder<R> getPredictDecoder() throws Exception {
        String factoryName = this.algorithmDefinition.getDecoderFactoryName();
        Optional<JsonNode> parameter = this.algorithmDefinition.getPredictDecoderParameter();
        return ((ExampleDecoderFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(parameter);
    }

    protected Scorer<R> getScorer(Map<String, InputStream> parameter) throws Exception {
        String factoryName = this.algorithmDefinition.getScorerFactoryName();
        return ((ScorerFactory<R>)Class.forName(factoryName).getDeclaredConstructor().newInstance())
                .apply(getVectorizer(), parameter);
    }


}
