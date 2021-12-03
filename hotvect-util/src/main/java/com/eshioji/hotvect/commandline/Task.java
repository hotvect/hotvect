package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.util.VerboseCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public abstract class Task extends VerboseCallable<Map<String, String>> {
    protected final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
    protected final Options opts;
    protected final MetricRegistry metricRegistry;

    public Task(Options opts, MetricRegistry metricRegistry) {
        this.opts = opts;
        this.metricRegistry = metricRegistry;
    }
}
