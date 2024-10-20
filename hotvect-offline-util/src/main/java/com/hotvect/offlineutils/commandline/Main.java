package com.hotvect.offlineutils.commandline;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.util.StatusPrinter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionReader;
import com.hotvect.utils.HyperparamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
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

    private static void configureLogFile(File logfile){
                LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

                FileAppender fileAppender = new FileAppender();
                fileAppender.setContext(loggerContext);
                fileAppender.setName("logfile");
                fileAppender.setFile(logfile.getAbsolutePath());

                PatternLayoutEncoder encoder = new PatternLayoutEncoder();
                encoder.setContext(loggerContext);
                encoder.setPattern("%r %thread %level - %msg%n");
                encoder.start();

                fileAppender.setEncoder(encoder);
                fileAppender.start();

                // attach the rolling file appender to the logger of your choice
                ch.qos.logback.classic.Logger logbackLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
                logbackLogger.addAppender(fileAppender);

                StatusPrinter.print(loggerContext);
            }

    public static void main(String[] args) {
        Slf4jReporter reporter = Slf4jReporter
                .forRegistry(METRIC_REGISTRY)
                .outputTo(LOGGER)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        try {
            Options opts = new Options();
            new CommandLine(opts).parseArgs(args);

            File logFileLocation = new File(opts.metadataLocation.getParentFile(), "hotvect-offline-utils.log");
            configureLogFile(logFileLocation);

            CommandlineUtility.expandTildaOnFileFields(opts);


            reporter.start(10, TimeUnit.SECONDS);
            Callable<Map<String, Object>> task = getTask(opts);
            Map<String, Object> metadata = task.call();
            metadata.put("logfile", logFileLocation.getAbsolutePath());
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
    private static String getTaskName(Options opts) {
        if (opts.encode) {
            return "encode";
        } else if (opts.predict) {
            return "predict";
        } else if (opts.generateStateTask != null) {
            return "generate-state";
        } else if (opts.audit) {
            return "audit";
        } else if (opts.performanceTest) {
            return "performance-test";
        } else if (opts.listAvailableTransformations) {
            return "list-available-transformations";
        } else {
            throw new UnsupportedOperationException("No command given. Available: encode, predict, generate-State, audit, performance-test, list-available-transformations");
        }
    }

    private static Callable<Map<String, Object>> getTask(Options opts) throws Exception {
        validate(opts);

        OfflineTaskContext offlineTaskContext = getOfflineTaskContext(opts);
        String taskName = getTaskName(opts);
        switch (taskName) {
            case "encode":
                return new EncodeTask<>(offlineTaskContext);
            case "predict":
                return new PredictTask<>(offlineTaskContext);
            case "generate-state":
                return new GenerateStateTask(offlineTaskContext);
            case "audit":
                return new AuditTask<>(offlineTaskContext);
            case "performance-test":
                return new PerformanceTestTask<>(offlineTaskContext);
//            case "list-available-transformations":
//                return new ListAvailableTransformationsTask<>(offlineTaskContext);
            default:
                throw new AssertionError("Unknown task: " + taskName);
        }
    }

    private static OfflineTaskContext getOfflineTaskContext(Options opts) throws IOException, MalformedAlgorithmException {
        checkArgument(opts.algorithmJar.exists() && opts.algorithmJar.isFile(), "Specified algorithm jar does not exist or is not a file: %s", opts.algorithmJar.getAbsolutePath());
        URL algoJarLocation = opts.algorithmJar.toURI().toURL();

        List<URL> classPath  = new ArrayList<>();
        if(!opts.additionalJarFiles.isEmpty()){
            LOGGER.info("Additional jar files specified:" + opts.additionalJarFiles);
        }

        for (File additionalJarFile : opts.additionalJarFiles) {
            classPath.add(additionalJarFile.toURI().toURL());
        }
        classPath.add(algoJarLocation);

        URLClassLoader algoClassLoader
                = new URLClassLoader(classPath.toArray(new URL[0]));

        Optional<AlgorithmDefinition> algorithmDefinition = readAlgorithmDefinitionFromFile(opts);
        if (algorithmDefinition.isEmpty()) {
            // No external algorithm definition, read it from the jar
            AlgorithmDefinition algoDef = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(opts.algorithmDefinition, algoClassLoader);
            algorithmDefinition = Optional.of(algoDef);
            LOGGER.info("Read algorithm definition from jar {} {}", opts.algorithmJar, algorithmDefinition.get());

        } else {
            LOGGER.info("Read customized algorithm definition:{}", algorithmDefinition.get());
        }

        Optional<JsonNode> rawAlgorithmDef = algorithmDefinition.map(AlgorithmDefinition::getRawAlgorithmDefinition);

        String taskName = getTaskName(opts);

        LOGGER.info("Original options specified:{}", opts);

        // We now override the options with the values from the algorithm definition
        // It can be specified at root level, or for each task which overrides root level
        // config.
        opts.maxThreads = HyperparamUtils.getOrDefault(
                rawAlgorithmDef,
                JsonNode::asInt,
                HyperparamUtils.getOrDefault(
                        rawAlgorithmDef,
                        JsonNode::asInt,
                        opts.maxThreads,
                        "hotvect_execution_parameters", "max_threads"
                ),
                "hotvect_execution_parameters", taskName, "max_threads"
        );

        opts.queueLength = HyperparamUtils.getOrDefault(
                rawAlgorithmDef,
                JsonNode::asInt,
                HyperparamUtils.getOrDefault(
                        rawAlgorithmDef,
                        JsonNode::asInt,
                        opts.queueLength,
                        "hotvect_execution_parameters", "queue_length"
                ),
                "hotvect_execution_parameters", taskName, "queue_length"
        );

        opts.batchSize = HyperparamUtils.getOrDefault(
                rawAlgorithmDef,
                JsonNode::asInt,
                HyperparamUtils.getOrDefault(
                        rawAlgorithmDef,
                        JsonNode::asInt,
                        opts.batchSize,
                        "hotvect_execution_parameters", "batch_size"
                ),
                "hotvect_execution_parameters", taskName, "batch_size"
        );

        // For safety reasons, samples can only be overwritten for predict and performance test task
        if ("performance-test".equals(taskName)) {
            opts.samples = HyperparamUtils.getOrDefault(
                    rawAlgorithmDef,
                    JsonNode::asInt,
                    opts.samples,
                    "hotvect_execution_parameters", taskName, "samples"
            );
        }

        if ("predict".equals(taskName)) {
            opts.samples = HyperparamUtils.getOrDefault(
                    rawAlgorithmDef,
                    JsonNode::asInt,
                    opts.samples,
                    "hotvect_execution_parameters", taskName, "samples"
            );
        }

        LOGGER.info("Options after possible overrides:{}", opts);

        return new OfflineTaskContext(algoClassLoader, METRIC_REGISTRY, opts, algorithmDefinition.get());
    }

    private static void validate(Options opts) {
        boolean exactlyOneTask = Stream.of(opts.encode, opts.predict, opts.audit, opts.performanceTest, opts.generateStateTask != null, opts.listAvailableTransformations).filter(x -> x).count() == 1;
        checkArgument(exactlyOneTask, "You must specify exactly one of: encode, test, audit, generate-state, list-available-transformations");
        // TODO do more
    }

    private static Optional<AlgorithmDefinition> readAlgorithmDefinitionFromFile(Options opts) throws IOException {
        if (opts.algorithmDefinition.toLowerCase(Locale.ROOT).endsWith(".json")) {
            File algorithmDefinitionFile = new File(opts.algorithmDefinition);
            checkState(algorithmDefinitionFile.exists(), "Algorithm definition file does not exist:" + algorithmDefinitionFile.getAbsolutePath());
            return Optional.of(new AlgorithmDefinitionReader().parse(Files.asCharSource(algorithmDefinitionFile, Charsets.UTF_8).read()));
        } else {
            // algorithm specified as its name, no algorithm definition specified as file
            return Optional.empty();
        }
    }
}
