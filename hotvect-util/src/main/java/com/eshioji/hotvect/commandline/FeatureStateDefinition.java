package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.core.transform.FeatureState;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface FeatureStateDefinition<R, S extends FeatureState> {
    GenerateStateTask<R> getGenerationTask(Options options, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition);
    Function<InputStream, S> getDeserializer();
    BiConsumer<OutputStream, S> getSerializer();
}
