package com.eshioji.hotvect.commandline;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;

public class Main {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final MetricRegistry METRIC_REGISTRY = new MetricRegistry();

    public static void main(String[] args) throws Exception {
        var opts = new Options();
        new CommandLine(opts).parseArgs(args);

        var reporter = Slf4jReporter
                .forRegistry(METRIC_REGISTRY)
                .outputTo(LOGGER)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        try {
            reporter.start(10, TimeUnit.SECONDS);
            var task = getTask(opts);
            var metadata = task.call();
            OM.writeValue(opts.metadataLocation, metadata);
            LOGGER.info("Wrote metadata: location={}, metadata={}", opts.metadataLocation, metadata);

        } finally {
            reporter.report();
            reporter.stop();
        }

    }

    private static <R> Task<R> getTask(Options opts) throws Exception {
        checkState(opts.encode ^ opts.predict, "Exactly one command (predict or encode) must be specified");
        var algorithmDefinitionFile = Path.of(opts.algorithmDefinition).toFile();
        checkState(algorithmDefinitionFile.exists(), "Algorithm definition file does not exist:" + algorithmDefinitionFile.getAbsolutePath());

        var algorithmDefinition = OM.readValue(algorithmDefinitionFile, AlgorithmDefinition.class);

        if (opts.encode) {
            return new EncodeTask<>(opts, METRIC_REGISTRY, algorithmDefinition);
        } else if (opts.predict) {
            return new PredictTask<>(opts, METRIC_REGISTRY, algorithmDefinition);
        } else {
            throw new UnsupportedOperationException("No command given. Available: encode or predict");
        }
    }

}
