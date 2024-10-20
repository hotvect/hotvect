package com.hotvect.offlineutils.commandline;

import com.codahale.metrics.MetricRegistry;
import com.hotvect.api.algodefinition.AlgorithmDefinition;

import java.net.URLClassLoader;

public class OfflineTaskContext {
    private final URLClassLoader classLoader;
    private final MetricRegistry metricRegistry;
    private final Options options;
    private final AlgorithmDefinition algorithmDefinition;

    public OfflineTaskContext(URLClassLoader classLoader, MetricRegistry metricRegistry, Options options, AlgorithmDefinition algorithmDefinition) {
        this.classLoader = classLoader;
        this.metricRegistry = metricRegistry;
        this.options = options;
        this.algorithmDefinition = algorithmDefinition;
    }

    public URLClassLoader getClassLoader() {
        return classLoader;
    }

    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    public Options getOptions() {
        return options;
    }

    public AlgorithmDefinition getAlgorithmDefinition() {
        return algorithmDefinition;
    }
}
