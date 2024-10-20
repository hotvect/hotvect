package com.hotvect.offlineutils.commandline.util.flatmap;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.base.Throwables;
import com.hotvect.offlineutils.commandline.CommandlineUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class FlatMapFile {
    private static final ObjectMapper OM;

    static {
        OM = new ObjectMapper();
        OM.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(FlatMapFile.class);
    private static final MetricRegistry METRIC_REGISTRY = new MetricRegistry();

    public static void main(String[] args) {
        Slf4jReporter reporter = Slf4jReporter
                .forRegistry(METRIC_REGISTRY)
                .outputTo(LOGGER)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        try {
            FlatMapOptions opts = new FlatMapOptions();
            new CommandLine(opts).parseArgs(args);
            CommandlineUtility.expandTildaOnFileFields(opts);


            reporter.start(10, TimeUnit.SECONDS);
            Callable<Map<String, Object>> task = new FlatMapTask(METRIC_REGISTRY, opts);
            Map<String, Object> metadata = task.call();
            OM.writeValue(opts.metadataLocation, metadata);
            LOGGER.info("Wrote metadata: location={}, metadata={}", opts.metadataLocation, metadata);

        }catch (Throwable e) {
            LOGGER.error("Exception encountered:", e);
            System.err.println(Throwables.getStackTraceAsString(e));
            System.exit(1);
        } finally {
            reporter.report();
            reporter.stop();
        }

    }
}
