package com.hotvect.offlineutils.commandline.util.flatmap;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.micrometer.core.instrument.logging.LoggingRegistryConfig;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.base.Throwables;
import com.hotvect.offlineutils.commandline.CommandlineUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
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
    private static final MeterRegistry METER_REGISTRY = LoggingMeterRegistry.builder(new LoggingRegistryConfig() {
        @Override
        public Duration step() {
            return Duration.ofSeconds(10);
        }
        @Override
        public String get(String key) {
            return null;
        }
    }).build();

    public static void main(String[] args) {
        try {
            FlatMapOptions opts = new FlatMapOptions();
            new CommandLine(opts).parseArgs(args);
            CommandlineUtility.expandTildaOnFileFields(opts);
            if (opts.metadataLocation.getName().endsWith(".json")) {
                throw new IllegalArgumentException("--metadata-path now expects a directory, not a .json file. Example: --metadata-path myrun.metadata");
            }
            CommandlineUtility.ensureDirectoryExists(opts.metadataLocation, "--metadata-path");

            Callable<Map<String, Object>> task = new FlatMapTask(METER_REGISTRY, opts);
            Map<String, Object> metadata = task.call();
            File metadataFile = CommandlineUtility.metadataJsonFile(opts.metadataLocation);
            OM.writeValue(metadataFile, metadata);
            LOGGER.info("Wrote metadata: location={}, metadata={}", metadataFile, metadata);

        }catch (Throwable e) {
            LOGGER.error("Exception encountered:", e);
            System.err.println(Throwables.getStackTraceAsString(e));
            System.exit(1);
        }

    }
}
