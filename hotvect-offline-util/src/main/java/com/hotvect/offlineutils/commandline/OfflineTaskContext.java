package com.hotvect.offlineutils.commandline;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URLClassLoader;

public record OfflineTaskContext(
        URLClassLoader classLoader,
        MeterRegistry meterRegistry,
        Options options,
        AlgorithmDefinition algorithmDefinition
) {
}
