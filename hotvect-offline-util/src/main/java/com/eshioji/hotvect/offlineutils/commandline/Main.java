package com.eshioji.hotvect.offlineutils.commandline;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

public class Main {
    private static final ObjectMapper OM;

    static {
        OM = new ObjectMapper();
        OM.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final MetricRegistry METRIC_REGISTRY = new MetricRegistry();

    public static void main(String[] args) throws Exception {
        Options opts = new Options();
        new CommandLine(opts).parseArgs(args);

        Slf4jReporter reporter = Slf4jReporter
                .forRegistry(METRIC_REGISTRY)
                .outputTo(LOGGER)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        try {
            reporter.start(10, TimeUnit.SECONDS);
            Callable<Map<String, String>> task = getTask(opts);
            Map<String, String> metadata = task.call();
            OM.writeValue(opts.metadataLocation, metadata);
            LOGGER.info("Wrote metadata: location={}, metadata={}", opts.metadataLocation, metadata);

        } finally {
            reporter.report();
            reporter.stop();
        }

    }

    private static Callable<Map<String, String>> getTask(Options opts) throws Exception {
        validate(opts);

        OfflineTaskContext offlineTaskContext = getOfflineTaskContext(opts);

        if (opts.encode) {
            return new EncodeTask<>(offlineTaskContext);
        } else if (opts.predict) {
            return new PredictTask<>(offlineTaskContext);
        } else if (opts.generateStateTask != null) {
            return (GenerateStateTask<?, ?, ?>) Class.forName(
                            opts.generateStateTask, true, offlineTaskContext.getClassLoader()
                    ).getDeclaredConstructor(OfflineTaskContext.class)
                    .newInstance(offlineTaskContext);
        }
//        else if (opts.audit) {
//            return new AuditTask<>(offlineTaskContext);
//        }
        else {
            throw new UnsupportedOperationException("No command given. Available: encode, predict or generate-state");

        }
    }

    private static OfflineTaskContext getOfflineTaskContext(Options opts) throws IOException {
        AlgorithmDefinition algorithmDefinition = readAlgorithmDefinition(opts);
        URL algoJarLocation = new File(opts.algorithmJar).toURI().toURL();
        ClassLoader algoClassLoader
                = new URLClassLoader(new URL[]{algoJarLocation});
        OfflineTaskContext offlineTaskContext = new OfflineTaskContext(algoClassLoader, METRIC_REGISTRY, opts, algorithmDefinition);
        return offlineTaskContext;
    }

    private static void validate(Options opts) {
        boolean exactlyOneTask = Stream.of(opts.encode, opts.predict, opts.audit, opts.generateStateTask != null).filter(x -> x).count() == 1;
        checkArgument(exactlyOneTask, "You must specify exactly one of: encode, predict, audit, generate-state");
        // TODO do more
    }

    private static AlgorithmDefinition readAlgorithmDefinition(Options opts) throws IOException {
        File algorithmDefinitionFile = new File(opts.algorithmDefinition);
        checkState(algorithmDefinitionFile.exists(), "Algorithm definition file does not exist:" + algorithmDefinitionFile.getAbsolutePath());

        return AlgorithmDefinition.parse(Files.asCharSource(algorithmDefinitionFile, Charsets.UTF_8).read());
    }

}
