package com.hotvect.offlineutils.commandline;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.util.StatusPrinter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.algodownload.ArtifactUriAlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.httpclient.EmsSnapshotExporter;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.experimentmanagement.httpclient.FileExperimentManagementStateSource;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.onlineutils.serving.EmsPredictionRuntime;
import com.hotvect.onlineutils.serving.FixedCompositionRuntime;
import com.hotvect.onlineutils.util.Closeables;
import com.hotvect.utils.AlgorithmDefinitionReader;
import com.hotvect.utils.HyperparamUtils;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardConfig;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    
    private static DropwizardConfig createDropwizardConfig() {
        return new DropwizardConfig() {
            @Override
            public String get(String key) {
                return null;
            }
            
            @Override
            public String prefix() {
                return "";
            }
        };
    }
    
    private static final MeterRegistry METER_REGISTRY = new DropwizardMeterRegistry(
            createDropwizardConfig(),
            METRIC_REGISTRY,
            HierarchicalNameMapper.DEFAULT,
            Clock.SYSTEM
    ) {
        @Override
        protected Double nullGaugeValue() {
            return Double.NaN;
        }
    };

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
        int exitCode = new CommandLine(new RootCommand()).execute(args);
        System.exit(exitCode);
    }

    static void validateOutputOrderingOptions(CommandSpec spec, boolean ordered, boolean unordered, int writerNumShards) {
        if (ordered && unordered) {
            throw new ParameterException(spec.commandLine(), "At most one of --ordered and --unordered may be specified.");
        }
        if (ordered && writerNumShards > 1) {
            throw new ParameterException(
                    spec.commandLine(),
                    "--writer-num-shards > 1 may only be used with --unordered. Ordered output always writes a single part file."
            );
        }
    }

    static void applyExecutionOptions(Options opts, ExecutionOptions execution) {
        opts.maxThreads = execution.maxThreads;
        opts.batchSize = execution.batchSize;
        opts.queueLength = execution.queueLength;
        opts.readQueueLength = execution.readQueueLength;
        opts.writeQueueLength = execution.writeQueueLength;
        opts.samples = execution.samples;
    }

    static void applyOutputOrderingOptions(Options opts, OutputOrderingOptions ordering) {
        opts.ordered = ordering.ordered;
        opts.unordered = ordering.unordered;
        opts.writerNumShards = ordering.writerNumShards;
    }

    static OfflineAlgorithmSource selectedComposedAlgorithmSource(
            CommandSpec spec,
            ComposedAlgorithmSourceOptions algorithmSource,
            List<File> domainModelJars) {
        if (algorithmSource.direct != null) {
            return new OfflineAlgorithmSource.Direct(
                    algorithmSource.direct.algorithmJar,
                    algorithmSource.direct.algorithmDefinition,
                    domainModelJars,
                    algorithmSource.direct.additionalJarFiles,
                    algorithmSource.direct.parameters);
        }
        if (algorithmSource.fixed != null) {
            return new OfflineAlgorithmSource.Fixed(
                    algorithmSource.fixed.composition,
                    domainModelJars);
        }
        if (!algorithmSource.ems.assignmentKeyJsonPointer.startsWith("/")) {
            throw new ParameterException(
                    spec.commandLine(),
                    "--assignment-key-json-pointer must be an RFC 6901 JSON Pointer.");
        }
        return new OfflineAlgorithmSource.Ems(
                algorithmSource.ems.emsSlot,
                algorithmSource.ems.emsState,
                algorithmSource.ems.assignmentKeyJsonPointer,
                domainModelJars);
    }

    static void applyAlgorithmSource(Options opts, OfflineAlgorithmSource algorithmSource) {
        opts.algorithmSource = Objects.requireNonNull(algorithmSource, "algorithmSource must not be null");
    }

    @Command(
            name = "hotvect-offline-util",
            description = "Hotvect offline utility.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    EncodeCommand.class,
                    PredictCommand.class,
                    AuditCommand.class,
                    GenerateStateCommand.class,
                    PerformanceTestCommand.class,
                    EmsSnapshotExportCommand.class
            }
    )
    public static final class RootCommand implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(System.err);
            return CommandLine.ExitCode.USAGE;
        }
    }

    @Command(
            name = "ems-snapshot-export",
            description = "Capture the active EMS state reachable from one prediction root slot.",
            mixinStandardHelpOptions = true)
    public static final class EmsSnapshotExportCommand implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @Option(names = {"--root-slot"}, required = true, description = "Root EMS slot to capture.")
        public String rootSlot;

        @Option(names = {"--output"}, required = true, description = "New snapshot JSON file to write atomically.")
        public File output;

        @Option(names = {"--ems-uri"}, required = true, description = "Configured EMS base URI.")
        public String emsUri;

        @Option(
                names = {"--ems-connect-timeout-seconds"},
                required = true,
                description = "EMS connection timeout in seconds.")
        public double emsConnectTimeoutSeconds;

        @Option(
                names = {"--ems-read-timeout-seconds"},
                required = true,
                description = "EMS response timeout in seconds.")
        public double emsReadTimeoutSeconds;

        @Option(
                names = {"--domain-model-jar"},
                description = "JAR loaded above captured artifact JARs for shared domain-model classes.",
                split = ",")
        public List<File> domainModelJars = new ArrayList<>();

        @Override
        public Integer call() throws Exception {
            if (rootSlot == null || rootSlot.isBlank()) {
                throw new ParameterException(spec.commandLine(), "--root-slot must not be blank.");
            }
            if (emsUri == null || emsUri.isBlank()) {
                throw new ParameterException(spec.commandLine(), "--ems-uri must not be blank.");
            }
            if (emsConnectTimeoutSeconds <= 0 || emsReadTimeoutSeconds <= 0) {
                throw new ParameterException(spec.commandLine(), "EMS HTTP timeouts must be positive.");
            }
            String bearerToken = System.getenv("HOTVECT_EMS_BEARER_TOKEN");
            if (bearerToken == null || bearerToken.isBlank()) {
                throw new ParameterException(
                        spec.commandLine(),
                        "HOTVECT_EMS_BEARER_TOKEN is required for EMS snapshot export.");
            }

            URLClassLoader domainModelClassLoader = null;
            try {
                ClassLoader parentClassLoader = Main.class.getClassLoader();
                if (!domainModelJars.isEmpty()) {
                    URL[] urls = new URL[domainModelJars.size()];
                    for (int index = 0; index < domainModelJars.size(); index++) {
                        File jar = domainModelJars.get(index);
                        checkArgument(jar.isFile(), "Domain-model JAR does not exist or is not a file: %s", jar);
                        urls[index] = jar.toURI().toURL();
                    }
                    domainModelClassLoader = new URLClassLoader(urls, parentClassLoader);
                    parentClassLoader = domainModelClassLoader;
                }

                ExperimentManagementServiceClient client = new ExperimentManagementServiceClient(
                        URI.create(emsUri),
                        Duration.ofMillis(Math.round(emsConnectTimeoutSeconds * 1000)),
                        Duration.ofMillis(Math.round(emsReadTimeoutSeconds * 1000)),
                        () -> bearerToken);
                EmsSnapshotExporter exporter = new EmsSnapshotExporter(
                        client,
                        new ArtifactUriAlgorithmDownloadClient(),
                        parentClassLoader,
                        METER_REGISTRY);
                var document = exporter.export(rootSlot, output.toPath());
                LOGGER.info(
                        "Exported EMS snapshot: rootSlot={}, slots={}, output={}",
                        rootSlot,
                        document.slots().keySet(),
                        output.getAbsolutePath());
                return 0;
            } finally {
                if (domainModelClassLoader != null) {
                    domainModelClassLoader.close();
                }
            }
        }
    }

    static int runTask(String taskName, Options opts) {
        Slf4jReporter reporter = Slf4jReporter
                .forRegistry(METRIC_REGISTRY)
                .outputTo(LOGGER)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        return doRunTask(taskName, opts, reporter);
    }

    private static int doRunTask(String taskName, Options opts, Slf4jReporter reporter) {
        ProgressJsonlReporter progressReporter = null;
        try {
            CommandlineUtility.expandTildaOnFileFields(opts);
            if (opts.metadataLocation.getName().endsWith(".json")) {
                throw new IllegalArgumentException("--metadata-path now expects a directory, not a .json file. Example: --metadata-path myrun.metadata");
            }
            File metadataDir = CommandlineUtility.ensureDirectoryExists(opts.metadataLocation, "--metadata-path");
            File logFileLocation = CommandlineUtility.logFile(metadataDir);
            configureLogFile(logFileLocation);

            reporter.start(20, TimeUnit.SECONDS);
            try (OfflineTaskContext offlineTaskContext = getOfflineTaskContext(taskName, opts)) {
                progressReporter = new ProgressJsonlReporter(
                        METRIC_REGISTRY,
                        OM,
                        metadataDir,
                        taskName,
                        opts,
                        offlineTaskContext.algorithmDefinition()
                );
                progressReporter.start();
                Callable<Map<String, Object>> task = switch (taskName) {
                    case "encode" -> new EncodeTask<>(offlineTaskContext);
                    case "predict" -> new PredictTask<>(offlineTaskContext);
                    case "generate-state" -> new GenerateStateTask(offlineTaskContext);
                    case "audit" -> new AuditTask<>(offlineTaskContext);
                    case "performance-test" -> new PerformanceTestTask<>(offlineTaskContext);
                    default -> throw new AssertionError("Unknown task: " + taskName);
                };

                Map<String, Object> metadata = task.call();
                metadata.put("logfile", logFileLocation.getAbsolutePath());

                File metadataFile = CommandlineUtility.metadataJsonFile(metadataDir);
                OM.writeValue(metadataFile, metadata);
                progressReporter.writeEnd(metadata);
                LOGGER.info("Wrote metadata: location={}, metadata={}", metadataFile, metadata);
                return 0;
            }

        } catch (Throwable e) {
            if (progressReporter != null) {
                progressReporter.writeFailure(e);
            }
            Throwable root = Throwables.getRootCause(e);
            if (root instanceof InterruptedException) {
                LOGGER.warn("Task was aborted");
                System.err.println("Task was aborted, check error logs.");
            } else {
                LOGGER.error("Exception encountered:", e);
                System.err.println(Throwables.getStackTraceAsString(e));
            }
            return 1;
        } finally {
            reporter.report();
            reporter.stop();
            if (progressReporter != null) {
                progressReporter.close();
            }
        }
    }

    private static OfflineTaskContext getOfflineTaskContext(String taskName, Options opts) throws Exception {
        OfflineAlgorithmSource source = Objects.requireNonNull(
                opts.algorithmSource,
                "algorithmSource must not be null");
        return switch (source) {
            case OfflineAlgorithmSource.Direct direct -> getDirectTaskContext(taskName, opts, direct);
            case OfflineAlgorithmSource.Fixed fixed -> {
                requireComposedTask(taskName, "Fixed composition");
                yield getFixedCompositionTaskContext(opts, taskName, fixed);
            }
            case OfflineAlgorithmSource.Ems ems -> {
                requireComposedTask(taskName, "EMS composition");
                yield getEmsPredictionTaskContext(opts, taskName, ems);
            }
        };
    }

    static OfflineTaskContext getDirectTaskContext(
            String taskName,
            Options opts,
            OfflineAlgorithmSource.Direct source) throws Exception {
        checkArgument(
                source.algorithmJar().exists() && source.algorithmJar().isFile(),
                "Specified algorithm jar does not exist or is not a file: %s",
                source.algorithmJar().getAbsolutePath());
        URLClassLoader domainModelClassLoader = null;
        URLClassLoader algoClassLoader = null;
        try {
            domainModelClassLoader = openDomainModelClassLoader(source.domainModelJars());
            ClassLoader parentClassLoader = domainModelClassLoader == null
                    ? Main.class.getClassLoader()
                    : domainModelClassLoader;

            URL algoJarLocation = source.algorithmJar().toURI().toURL();
            List<URL> classPath = new ArrayList<>();
            if (!source.additionalJars().isEmpty()) {
                LOGGER.info("Additional jar files specified:{}", source.additionalJars());
            }
            for (File additionalJarFile : source.additionalJars()) {
                classPath.add(additionalJarFile.toURI().toURL());
            }
            classPath.add(algoJarLocation);

            algoClassLoader = new URLClassLoader(classPath.toArray(new URL[0]), parentClassLoader);

            Optional<AlgorithmDefinition> algorithmDefinition = readAlgorithmDefinitionFromFile(source);
            if (algorithmDefinition.isEmpty()) {
                // No external algorithm definition, read it from the jar
                AlgorithmDefinition algoDef = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(
                        source.algorithmDefinition(),
                        algoClassLoader,
                        new AlgorithmDefinitionReader(InputSemantic.OFFLINE));
                algorithmDefinition = Optional.of(algoDef);
                LOGGER.info("Read algorithm definition from jar {} {}", source.algorithmJar(), algorithmDefinition.get());

            } else {
                LOGGER.info("Read customized algorithm definition:{}", algorithmDefinition.get());
            }

            Optional<JsonNode> rawAlgorithmDef = algorithmDefinition.map(AlgorithmDefinition::rawAlgorithmDefinition);

            LOGGER.info("Original options specified:{}", opts);

            // Fill unset execution options from the algorithm definition. Explicit CLI values win.
            opts.maxThreads = resolveExecutionIntOption(rawAlgorithmDef, opts.maxThreads, taskName, "max_threads");
            opts.queueLength = resolveExecutionIntOption(rawAlgorithmDef, opts.queueLength, taskName, "queue_length");
            opts.readQueueLength = resolveExecutionIntOption(rawAlgorithmDef, opts.readQueueLength, taskName, "read_queue_length");
            opts.writeQueueLength = resolveExecutionIntOption(rawAlgorithmDef, opts.writeQueueLength, taskName, "write_queue_length");
            opts.batchSize = resolveExecutionIntOption(rawAlgorithmDef, opts.batchSize, taskName, "batch_size");

            // For safety reasons, samples can only be read from the algorithm definition for
            // predict and performance-test, and only when CLI leaves them unset.
            if ("performance-test".equals(taskName)) {
                opts.samples = resolveTaskScopedExecutionIntOption(rawAlgorithmDef, opts.samples, taskName, "samples");
                opts.samplePoolSize = resolveTaskScopedExecutionIntOption(
                        rawAlgorithmDef,
                        opts.samplePoolSize,
                        taskName,
                        "sample_pool_size"
                );
                opts.performanceTestWorkloadMode = resolvePerformanceTestWorkloadMode(rawAlgorithmDef, opts.performanceTestWorkloadMode);
            }

            if ("predict".equals(taskName)) {
                opts.samples = resolveTaskScopedExecutionIntOption(rawAlgorithmDef, opts.samples, taskName, "samples");
            }

            LOGGER.info("Options after possible overrides:{}", opts);

            return OfflineTaskContext.forDirect(
                    algoClassLoader,
                    METER_REGISTRY,
                    opts,
                    algorithmDefinition.get(),
                    domainModelClassLoader);
        } catch (Exception | Error failure) {
            Closeables.closeAfterFailure(failure, algoClassLoader, domainModelClassLoader);
            throw failure;
        }
    }

    private static void requireComposedTask(String taskName, String source) {
        if (!"predict".equals(taskName) && !"performance-test".equals(taskName)) {
            throw new IllegalArgumentException(source + " is supported only for predict and performance-test");
        }
    }

    private static OfflineTaskContext getEmsPredictionTaskContext(
            Options opts,
            String taskName,
            OfflineAlgorithmSource.Ems source) throws Exception {
        URLClassLoader domainModelClassLoader = null;
        EmsPredictionRuntime runtime = null;
        try {
            domainModelClassLoader = openDomainModelClassLoader(source.domainModelJars());
            ClassLoader parentClassLoader = domainModelClassLoader == null
                    ? Main.class.getClassLoader()
                    : domainModelClassLoader;

            Path emsStatePath = source.state().toPath().toAbsolutePath().normalize();
            runtime = buildEmsPredictionRuntime(opts, source, parentClassLoader, emsStatePath, taskName);
            List<AlgorithmDefinition> rootDefinitions = runtime.rootDefinitions();
            return OfflineTaskContext.forEmsPrediction(
                    METER_REGISTRY,
                    opts,
                    rootDefinitions.get(0),
                    runtime,
                    domainModelClassLoader,
                    emsStatePath.toUri().toString(),
                    source.rootSlot(),
                    source.assignmentKeyJsonPointer());
        } catch (Exception | Error failure) {
            Closeables.closeAfterFailure(failure, runtime, domainModelClassLoader);
            throw failure;
        }
    }

    private static EmsPredictionRuntime buildEmsPredictionRuntime(
            Options opts,
            OfflineAlgorithmSource.Ems source,
            ClassLoader parentClassLoader,
            Path emsStatePath,
            String taskName) throws Exception {
        EmsPredictionRuntime.Builder builder = EmsPredictionRuntime.builder()
                .rootSlot(source.rootSlot())
                .stateSource(new FileExperimentManagementStateSource(emsStatePath))
                .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                .algorithmParentClassLoader(parentClassLoader)
                .executionContext(managedExecutionContext(taskName, opts))
                .resolveExecutionContext(definitions -> {
                    applyManagedExecutionOptions(opts, definitions, taskName);
                    return managedExecutionContext(taskName, opts);
                })
                .enableFeatureLogging(opts.logFeatures)
                .meterRegistry(METER_REGISTRY);
        return builder.build();
    }

    private static OfflineTaskContext getFixedCompositionTaskContext(
            Options opts,
            String taskName,
            OfflineAlgorithmSource.Fixed source) throws Exception {
        checkArgument(
                source.composition().exists() && source.composition().isFile(),
                "Fixed composition does not exist or is not a file: %s",
                source.composition().getAbsolutePath());

        URLClassLoader domainModelClassLoader = null;
        FixedCompositionRuntime runtime = null;
        try {
            domainModelClassLoader = openDomainModelClassLoader(source.domainModelJars());
            ClassLoader parentClassLoader = domainModelClassLoader == null
                    ? Main.class.getClassLoader()
                    : domainModelClassLoader;

            runtime = buildFixedCompositionRuntime(opts, source, parentClassLoader, taskName);
            AlgorithmDefinition rootDefinition = runtime.rootDefinition();
            return OfflineTaskContext.forFixedComposition(
                    METER_REGISTRY,
                    opts,
                    rootDefinition,
                    runtime,
                    domainModelClassLoader);
        } catch (Exception | Error failure) {
            Closeables.closeAfterFailure(failure, runtime, domainModelClassLoader);
            throw failure;
        }
    }

    private static FixedCompositionRuntime buildFixedCompositionRuntime(
            Options opts,
            OfflineAlgorithmSource.Fixed source,
            ClassLoader parentClassLoader,
            String taskName) throws Exception {
        FixedCompositionRuntime.Builder builder = FixedCompositionRuntime.builder()
                .composition(source.composition().toPath())
                .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                .algorithmParentClassLoader(parentClassLoader)
                .executionContext(managedExecutionContext(taskName, opts))
                .resolveExecutionContext(definitions -> {
                    applyManagedExecutionOptions(opts, definitions, taskName);
                    return managedExecutionContext(taskName, opts);
                })
                .enableFeatureLogging(opts.logFeatures)
                .meterRegistry(METER_REGISTRY);
        return builder.build();
    }

    private static URLClassLoader openDomainModelClassLoader(List<File> domainModelJars) throws IOException {
        if (domainModelJars.isEmpty()) {
            return null;
        }
        URL[] urls = new URL[domainModelJars.size()];
        for (int index = 0; index < domainModelJars.size(); index++) {
            File jar = domainModelJars.get(index);
            checkArgument(jar.isFile(), "Domain-model JAR does not exist or is not a file: %s", jar);
            urls[index] = jar.toURI().toURL();
        }
        return new URLClassLoader(urls, Main.class.getClassLoader());
    }

    private static ExecutionContext managedExecutionContext(String taskName, Options opts) {
        if ("predict".equals(taskName)) {
            return ExecutionContext.batch(InputSemantic.OFFLINE);
        }
        return ExecutionContext.of(
                PerformanceTestTask.resolveWorkloadMode(opts.performanceTestWorkloadMode),
                InputSemantic.OFFLINE);
    }

    static void applyManagedExecutionOptions(
            Options opts,
            List<AlgorithmDefinition> definitions,
            String taskName) {
        requireCompatibleOutputOrdering(opts, definitions, taskName);
        opts.maxThreads = resolveConsistentInt(definitions, opts.maxThreads, taskName, "max_threads", false);
        opts.queueLength = resolveConsistentInt(definitions, opts.queueLength, taskName, "queue_length", false);
        opts.batchSize = resolveConsistentInt(definitions, opts.batchSize, taskName, "batch_size", false);
        opts.samples = resolveConsistentInt(definitions, opts.samples, taskName, "samples", true);
        if ("performance-test".equals(taskName)) {
            opts.samplePoolSize = resolveConsistentInt(
                    definitions,
                    opts.samplePoolSize,
                    taskName,
                    "sample_pool_size",
                    true);
            opts.performanceTestWorkloadMode = resolveConsistentValue(
                    definitions, taskName, "workload_mode",
                    raw -> PerformanceTestTask.resolveWorkloadMode(
                            resolvePerformanceTestWorkloadMode(raw, opts.performanceTestWorkloadMode)))
                    .name().toLowerCase(Locale.ROOT);
        } else if ("predict".equals(taskName)) {
            if (!opts.ordered && !opts.unordered) {
                opts.ordered = resolveConsistentValue(
                        definitions, taskName, "ordered",
                        raw -> HyperparamUtils.getOrDefault(
                                raw, JsonNode::asBoolean, false, "hotvect_execution_parameters", taskName, "ordered"));
                opts.unordered = !opts.ordered;
            }
            if (!opts.ordered) {
                opts.readQueueLength = resolveConsistentInt(
                        definitions, opts.readQueueLength, taskName, "read_queue_length", false);
                opts.writeQueueLength = resolveConsistentInt(
                        definitions, opts.writeQueueLength, taskName, "write_queue_length", false);
                opts.writerNumShards = resolveConsistentInt(
                        definitions, opts.writerNumShards > 0 ? opts.writerNumShards : -1,
                        taskName, "writer_num_shards", true);
                // Reader concurrency has no CLI override; PredictTask reads the common value from its root.
                resolveConsistentInt(definitions, -1, taskName, "reader_threads", true);
            }
        }
    }

    private static void requireCompatibleOutputOrdering(
            Options opts,
            List<AlgorithmDefinition> definitions,
            String taskName) {
        if (!opts.requireUnorderedOutput || !"predict".equals(taskName)) {
            return;
        }
        boolean ordered = definitions.stream().anyMatch(definition -> definition.rawAlgorithmDefinition()
                .path("hotvect_execution_parameters").path("predict").path("ordered").asBoolean(false));
        if (ordered) {
            throw new IllegalArgumentException(
                    "Parallel one-shot prediction requires unordered output, but the composed root declares "
                            + "hotvect_execution_parameters.predict.ordered=true");
        }
    }

    private static int resolveConsistentInt(
            List<AlgorithmDefinition> definitions,
            int cliValue,
            String taskName,
            String optionName,
            boolean taskScoped) {
        if (cliValue != -1) {
            return cliValue;
        }
        return resolveConsistentValue(definitions, taskName, optionName, raw -> taskScoped
                ? resolveTaskScopedExecutionIntOption(raw, -1, taskName, optionName)
                : resolveExecutionIntOption(raw, -1, taskName, optionName));
    }

    private static <T> T resolveConsistentValue(
            List<AlgorithmDefinition> definitions,
            String taskName,
            String optionName,
            Function<Optional<JsonNode>, T> resolve) {
        Set<T> values = definitions.stream()
                .map(AlgorithmDefinition::rawAlgorithmDefinition)
                .map(Optional::of)
                .map(resolve)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (values.size() != 1) {
            throw new IllegalArgumentException(
                    "Composed root algorithms disagree on hotvect execution option " + taskName + "." + optionName
                            + ": " + values);
        }
        return values.iterator().next();
    }

    static String resolvePerformanceTestWorkloadMode(Optional<JsonNode> rawAlgorithmDef, String cliValue) {
        if (cliValue != null && !cliValue.isBlank()) {
            return cliValue;
        }
        return HyperparamUtils.getOrDefault(
                rawAlgorithmDef,
                JsonNode::asText,
                cliValue,
                "hotvect_execution_parameters", "performance-test", "workload_mode"
        );
    }

    static int resolveExecutionIntOption(
            Optional<JsonNode> rawAlgorithmDef,
            int cliValue,
            String taskName,
            String optionName
    ) {
        if (cliValue != -1) {
            return cliValue;
        }
        return HyperparamUtils.getOrDefault(
                rawAlgorithmDef,
                JsonNode::asInt,
                HyperparamUtils.getOrDefault(
                        rawAlgorithmDef,
                        JsonNode::asInt,
                        cliValue,
                        "hotvect_execution_parameters", optionName
                ),
                "hotvect_execution_parameters", taskName, optionName
        );
    }

    static int resolveTaskScopedExecutionIntOption(
            Optional<JsonNode> rawAlgorithmDef,
            int cliValue,
            String taskName,
            String optionName
    ) {
        if (cliValue != -1) {
            return cliValue;
        }
        return HyperparamUtils.getOrDefault(
                rawAlgorithmDef,
                JsonNode::asInt,
                cliValue,
                "hotvect_execution_parameters", taskName, optionName
        );
    }

    private static Optional<AlgorithmDefinition> readAlgorithmDefinitionFromFile(
            OfflineAlgorithmSource.Direct source) throws IOException {
        if (source.algorithmDefinition().toLowerCase(Locale.ROOT).endsWith(".json")) {
            File algorithmDefinitionFile = new File(source.algorithmDefinition());
            checkState(algorithmDefinitionFile.exists(), "Algorithm definition file does not exist:" + algorithmDefinitionFile.getAbsolutePath());
            return Optional.of(new AlgorithmDefinitionReader(InputSemantic.OFFLINE)
                    .parse(Files.asCharSource(algorithmDefinitionFile, Charsets.UTF_8).read()));
        } else {
            // algorithm specified as its name, no algorithm definition specified as file
            return Optional.empty();
        }
    }

    public static final class AlgorithmJarAndDefinitionOptions {
        @Option(names = {"--algorithm-jar"}, required = true, description = "The jar containing the algorithm.")
        public File algorithmJar;

        @Option(
                names = {"--algorithm-definition"},
                required = true,
                description = "Either the algorithm name as string, or a path to a JSON file containing the custom algorithm definition."
        )
        public String algorithmDefinition;

        @Option(
                names = {"--additional-jars"},
                description = "Additional jars that should be made available during processing.",
                split = ","
        )
        public List<File> additionalJarFiles = new ArrayList<>();
    }

    public static final class ComposedAlgorithmSourceOptions {
        @ArgGroup(exclusive = false, multiplicity = "1")
        public DirectAlgorithmSourceOptions direct;

        @ArgGroup(exclusive = false, multiplicity = "1")
        public FixedAlgorithmSourceOptions fixed;

        @ArgGroup(exclusive = false, multiplicity = "1")
        public EmsAlgorithmSourceOptions ems;
    }

    public static final class DirectAlgorithmSourceOptions {
        @Option(names = {"--algorithm-jar"}, required = true, description = "The local JAR containing the algorithm.")
        public File algorithmJar;

        @Option(
                names = {"--algorithm-definition"},
                required = true,
                description = "Local algorithm name or a path to a complete algorithm-definition JSON file."
        )
        public String algorithmDefinition;

        @Option(
                names = {"--additional-jars"},
                description = "Additional local JARs made available during processing.",
                split = ","
        )
        public List<File> additionalJarFiles = new ArrayList<>();

        @Option(
                names = {"--parameters"},
                description = "Path to parameter package file to be used when the algorithm requires one."
        )
        public File parameters;
    }

    public static final class FixedAlgorithmSourceOptions {
        @Option(
                names = {"--composition"},
                required = true,
                description = "Strict fixed-composition JSON (root, algorithms, slot_bindings) for offline predict or performance-test."
        )
        public File composition;

    }

    public static final class EmsAlgorithmSourceOptions {
        @Option(names = {"--ems-slot"}, required = true, description = "Root EMS slot to route for every input record.")
        public String emsSlot;

        @Option(
                names = {"--ems-state"},
                required = true,
                description = "Pinned local EMS active-state snapshot JSON document."
        )
        public File emsState;

        @Option(
                names = {"--assignment-key-json-pointer"},
                required = true,
                description = "RFC 6901 JSON Pointer locating each input record's EMS assignment key."
        )
        public String assignmentKeyJsonPointer;

    }

    public static final class DomainModelJarOptions {
        @Option(
                names = {"--domain-model-jar"},
                description = "JAR loaded above every direct, EMS, or fixed-composition artifact for shared domain-model classes.",
                split = ","
        )
        public List<File> jars = new ArrayList<>();
    }

    public static final class ParametersOption {
        @Option(names = {"--parameters"}, required = true, description = "Path to parameter package file to be used.")
        public File parameters;
    }

    public static final class OptionalParametersOption {
        @Option(
                names = {"--parameters"},
                description = "Path to parameter package file to be used when the algorithm requires one."
        )
        public File parameters;
    }

    public static final class MetadataOption {
        @Option(
                names = {"--metadata-path"},
                paramLabel = "METADATA_DIR",
                description = "Directory where metadata.json and hotvect-offline-utils.log will be written.",
                defaultValue = "metadata"
        )
        public File metadataLocation = new File("metadata");
    }

    public static final class DestinationOption {
        @Option(
                names = {"--dest"},
                required = true,
                paramLabel = "DESTINATION_PATH",
                description = "Destination path. For encode, predict, and audit, this is a directory containing part files (part-00000<ext>, part-00001<ext>, ...). Ordered predict/audit write a single part file (part-00000<ext>). For generate-state it may be a file or directory depending on the state generator."
        )
        public File destinationFile;
    }

    public static final class SourceFilesOption {
        /**
         * Wrapper type so picocli treats {@code --source} as a single-occurrence option.
         *
         * Picocli renders Map-typed options as repeatable in usage ("[--source]..."), even if
         * a custom parameterConsumer rejects multiple occurrences. Using a dedicated wrapper
         * keeps the parsed structure while making help/usage match actual behavior.
         */
        public static final class SourceFilesSpec {
            public final Map<String, List<File>> files;

            public SourceFilesSpec(Map<String, List<File>> files) {
                this.files = files;
            }
        }

        @Option(
                names = {"--source"},
                required = true,
                paramLabel = "SOURCE",
                description = "Data source paths (files or directories). Directories are traversed recursively. "
                        + "Format: JSON starting with '{' or '[' (e.g., '{\"type\":[\"file\"]}' or '[\"file1\",\"file2\"]') "
                        + "or comma-separated paths (file1,file2).",
                parameterConsumer = SourceFileConsumer.class
        )
        public SourceFilesSpec sourceFiles;
    }

    public static final class EncodeInputOptions {
        @Option(
                names = {"--source"},
                paramLabel = "SOURCE",
                description = "Data source paths (files or directories). Directories are traversed recursively. "
                        + "Format: JSON starting with '{' or '[' (e.g., '{\"type\":[\"file\"]}' or '[\"file1\",\"file2\"]') "
                        + "or comma-separated paths (file1,file2). Must be used together with --dest.",
                parameterConsumer = SourceFileConsumer.class
        )
        public SourceFilesOption.SourceFilesSpec sourceFiles;

        @Option(
                names = {"--dest"},
                paramLabel = "DESTINATION_PATH",
                description = "Destination directory containing encoded part files. Must be used together with --source."
        )
        public File destinationFile;

        @Option(
                names = {"--source-dest-mappings"},
                paramLabel = "FILE",
                description = "JSON file containing source/destination mappings. Cannot be combined with --source or --dest."
        )
        public File sourceDestMappingsFile;
    }

    public static final class ExecutionOptions {
        @Option(names = {"--max-threads"}, description = "Number of threads to be used for processing.", defaultValue = "-1")
        public int maxThreads = -1;

        @Option(names = {"--batch-size"}, description = "Size of the micro-batch used while processing.", defaultValue = "-1")
        public int batchSize = -1;

        @Option(names = {"--queue-length"}, description = "Size of the queues used for file IO.", defaultValue = "-1")
        public int queueLength = -1;

        @Option(names = {"--read-queue-length"}, description = "Size of the read queue used for unordered file IO.", defaultValue = "-1")
        public int readQueueLength = -1;

        @Option(names = {"--write-queue-length"}, description = "Size of the write queue used for unordered file IO.", defaultValue = "-1")
        public int writeQueueLength = -1;

        @Option(names = {"--samples"}, paramLabel = "N", description = "Number of records to process (-1 for all).", defaultValue = "-1")
        public int samples = -1;
    }

    public static final class OutputOrderingOptions {
        @Option(names = {"--ordered"}, description = "Whether the order in the output should strictly follow the order in the input.")
        public boolean ordered;

        @Option(names = {"--unordered"}, description = "Allow unordered internal processing. Output is written as part files under the destination directory, and global row order is not preserved.")
        public boolean unordered;

        @Option(names = {"--writer-num-shards"}, description = "Number of unordered output part files. <=0: auto-determine count (minimum 1). >=1: explicit count.", defaultValue = "-1")
        public int writerNumShards = -1;
    }

    public static final class SourceFileConsumer implements CommandLine.IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> args, ArgSpec argSpec, CommandSpec commandSpec) {
            if (args.isEmpty()) {
                throw new ParameterException(commandSpec.commandLine(), "No value provided for --source");
            }

            SourceFilesOption.SourceFilesSpec current = argSpec.getValue();
            if (current != null) {
                throw new ParameterException(commandSpec.commandLine(), "--source option can only be specified once");
            }

            String arg = args.pop();

            Map<String, List<File>> sourceFiles;
            if (arg.startsWith("{") || arg.startsWith("[")) {
                sourceFiles = parseJsonInput(arg, commandSpec);
            } else {
                sourceFiles = parseCommaSeparatedInput(arg, commandSpec);
            }

            argSpec.setValue(new SourceFilesOption.SourceFilesSpec(sourceFiles));
        }

        private Map<String, List<File>> parseJsonInput(String json, CommandSpec commandSpec) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                if (json.startsWith("[")) {
                    List<String> defaultFiles = mapper.readValue(json, new TypeReference<>() {});
                    Map<String, List<File>> result = new HashMap<>();
                    result.put("default", defaultFiles.stream().map(File::new).collect(Collectors.toList()));
                    return result;
                }
                Map<String, List<String>> typedInput = mapper.readValue(json, new TypeReference<>() {});
                Map<String, List<File>> result = new HashMap<>();

                for (Map.Entry<String, List<String>> entry : typedInput.entrySet()) {
                    List<File> files = new ArrayList<>();
                    for (String path : entry.getValue()) {
                        files.add(new File(path));
                    }
                    result.put(entry.getKey(), files);
                }
                return result;
            } catch (Exception e) {
                throw new ParameterException(commandSpec.commandLine(), "Invalid JSON format for --source", e);
            }
        }

        private Map<String, List<File>> parseCommaSeparatedInput(String input, CommandSpec commandSpec) {
            if (input.contains("{") || input.contains("}")) {
                throw new ParameterException(
                        commandSpec.commandLine(),
                        "--source expects either JSON (starting with '{' or '[') or comma-separated paths. " +
                                "Input did not start with '{' or '[' so it was parsed as comma-separated, but " +
                                "contains '{' or '}' which are not allowed as paths in hotvect. " +
                                "This likely indicates malformed JSON with outer quotes. " +
                                "Got: \"" + input + "\". " +
                                "Expected format: --source '{\"key\":[\"value\"]}'"
                );
            }

            List<File> files = new ArrayList<>();
            for (String path : input.split(",")) {
                files.add(new File(path.trim()));
            }
            return Collections.singletonMap("default", files);
        }
    }

    @Command(name = "encode", description = "Extract features from source data files and encode it.", mixinStandardHelpOptions = true)
    public static final class EncodeCommand implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @Mixin public AlgorithmJarAndDefinitionOptions algo = new AlgorithmJarAndDefinitionOptions();
        @Mixin public OptionalParametersOption parameters = new OptionalParametersOption();
        @Mixin public ExecutionOptions execution = new ExecutionOptions();
        @Mixin public OutputOrderingOptions ordering = new OutputOrderingOptions();
        @Mixin public EncodeInputOptions input = new EncodeInputOptions();
        @Mixin public MetadataOption metadata = new MetadataOption();

        @Option(names = {"--dest-schema-description"}, paramLabel = "DEST_SCHEMA_DESCRIPTION_FILE", description = "The file to which the schema description of the destination file will be written.")
        public File schemaDescriptionFile;

        @Override
        public Integer call() {
            validateOutputOrderingOptions(spec, ordering.ordered, ordering.unordered, ordering.writerNumShards);
            validateEncodeInputOptions(spec, input);
            boolean mappingsMode = input.sourceDestMappingsFile != null;

            Options opts = new Options();
            applyAlgorithmSource(
                    opts,
                    new OfflineAlgorithmSource.Direct(
                            algo.algorithmJar,
                            algo.algorithmDefinition,
                            List.of(),
                            algo.additionalJarFiles,
                            parameters.parameters));
            applyExecutionOptions(opts, execution);
            if (mappingsMode) {
                opts.sourceDestMappings = readSourceDestMappings(spec, input.sourceDestMappingsFile);
            } else {
                opts.sourceFiles = input.sourceFiles.files;
                opts.destinationFile = input.destinationFile;
            }
            opts.metadataLocation = metadata.metadataLocation;
            applyOutputOrderingOptions(opts, ordering);
            opts.schemaDescriptionFile = schemaDescriptionFile;
            return runTask("encode", opts);
        }
    }

    static void validateEncodeInputOptions(CommandSpec spec, EncodeInputOptions input) {
        boolean mappingsMode = input.sourceDestMappingsFile != null;
        boolean singleSourceMode = input.sourceFiles != null || input.destinationFile != null;
        if (mappingsMode && singleSourceMode) {
            throw new ParameterException(
                    spec.commandLine(),
                    "--source-dest-mappings cannot be combined with --source or --dest."
            );
        }
        if (!mappingsMode && (input.sourceFiles == null || input.destinationFile == null)) {
            throw new ParameterException(
                    spec.commandLine(),
                    "Encode requires either --source together with --dest, or --source-dest-mappings."
            );
        }
    }

    static List<SourceDestMapping> readSourceDestMappings(CommandSpec spec, File mappingsFile) {
        final List<SourceDestMapping> mappings;
        try {
            mappings = OM.readValue(mappingsFile, new TypeReference<>() {});
        } catch (IOException e) {
            throw new ParameterException(
                    spec.commandLine(),
                    "Failed to read --source-dest-mappings from " + mappingsFile + ": " + e.getMessage(),
                    e
            );
        }

        if (mappings == null || mappings.isEmpty()) {
            throw new ParameterException(spec.commandLine(), "--source-dest-mappings must contain at least one mapping.");
        }

        Set<Path> destinations = new HashSet<>();
        for (int index = 0; index < mappings.size(); index++) {
            SourceDestMapping mapping = mappings.get(index);
            if (mapping == null) {
                throw new ParameterException(spec.commandLine(), "Mapping at index " + index + " must be an object.");
            }
            if (mapping.sources() == null || mapping.sources().isEmpty()
                    || mapping.sources().stream().anyMatch(source -> source == null || source.getPath().isBlank())) {
                throw new ParameterException(
                        spec.commandLine(),
                        "Mapping at index " + index + " must contain at least one non-blank source."
                );
            }
            if (mapping.dest() == null || mapping.dest().getPath().isBlank()) {
                throw new ParameterException(spec.commandLine(), "Mapping at index " + index + " must contain dest.");
            }
            Path normalizedDest = mapping.dest().toPath().toAbsolutePath().normalize();
            if (!destinations.add(normalizedDest)) {
                throw new ParameterException(
                        spec.commandLine(),
                        "Duplicate destination in --source-dest-mappings: " + mapping.dest()
                );
            }
        }
        return List.copyOf(mappings);
    }

    @Command(name = "predict", description = "Perform prediction (test) on the source file.", mixinStandardHelpOptions = true)
    public static final class PredictCommand implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @ArgGroup(exclusive = true, multiplicity = "1")
        public ComposedAlgorithmSourceOptions algorithmSource;
        @Mixin public DomainModelJarOptions domainModelJars = new DomainModelJarOptions();
        @Mixin public ExecutionOptions execution = new ExecutionOptions();
        @Mixin public OutputOrderingOptions ordering = new OutputOrderingOptions();
        @Mixin public SourceFilesOption sources = new SourceFilesOption();
        @Mixin public DestinationOption destination = new DestinationOption();
        @Mixin public MetadataOption metadata = new MetadataOption();

        @Option(names = {"--log-features"}, description = "Enable feature logging during prediction for debugging.")
        public boolean logFeatures;

        @Option(
                names = {"--include-feature-store-responses"},
                description = "Include feature store responses in predict output under additional_properties.__feature_store_responses."
        )
        public boolean includeFeatureStoreResponses;

        @Option(names = {"--require-unordered-output"}, hidden = true)
        public boolean requireUnorderedOutput;

        @Override
        public Integer call() {
            validateOutputOrderingOptions(spec, ordering.ordered, ordering.unordered, ordering.writerNumShards);
            Options opts = new Options();
            applyAlgorithmSource(
                    opts,
                    selectedComposedAlgorithmSource(spec, algorithmSource, domainModelJars.jars));
            applyExecutionOptions(opts, execution);
            opts.sourceFiles = sources.sourceFiles.files;
            opts.destinationFile = destination.destinationFile;
            opts.metadataLocation = metadata.metadataLocation;
            applyOutputOrderingOptions(opts, ordering);
            opts.requireUnorderedOutput = requireUnorderedOutput;
            opts.logFeatures = logFeatures;
            opts.includeFeatureStoreResponses = includeFeatureStoreResponses;
            return runTask("predict", opts);
        }
    }

    @Command(name = "audit", description = "Produce audit output from source file.", mixinStandardHelpOptions = true)
    public static final class AuditCommand implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @Mixin public AlgorithmJarAndDefinitionOptions algo = new AlgorithmJarAndDefinitionOptions();
        @Mixin public ParametersOption parameters = new ParametersOption();
        @Mixin public ExecutionOptions execution = new ExecutionOptions();
        @Mixin public OutputOrderingOptions ordering = new OutputOrderingOptions();
        @Mixin public SourceFilesOption sources = new SourceFilesOption();
        @Mixin public DestinationOption destination = new DestinationOption();
        @Mixin public MetadataOption metadata = new MetadataOption();

        @Option(
                names = {"--include-feature-store-responses"},
                description = "Include feature store responses in audit output under additional_properties.__feature_store_responses."
        )
        public boolean includeFeatureStoreResponses;

        @Override
        public Integer call() {
            validateOutputOrderingOptions(spec, ordering.ordered, ordering.unordered, ordering.writerNumShards);
            Options opts = new Options();
            applyAlgorithmSource(
                    opts,
                    new OfflineAlgorithmSource.Direct(
                            algo.algorithmJar,
                            algo.algorithmDefinition,
                            List.of(),
                            algo.additionalJarFiles,
                            parameters.parameters));
            applyExecutionOptions(opts, execution);
            opts.sourceFiles = sources.sourceFiles.files;
            opts.destinationFile = destination.destinationFile;
            opts.metadataLocation = metadata.metadataLocation;
            applyOutputOrderingOptions(opts, ordering);
            opts.includeFeatureStoreResponses = includeFeatureStoreResponses;
            return runTask("audit", opts);
        }
    }

    @Command(name = "generate-state", description = "Generate state (algorithm must have generator_factory_classname).", mixinStandardHelpOptions = true)
    public static final class GenerateStateCommand implements Callable<Integer> {
        @Mixin public AlgorithmJarAndDefinitionOptions algo = new AlgorithmJarAndDefinitionOptions();
        @Mixin public SourceFilesOption sources = new SourceFilesOption();
        @Mixin public DestinationOption destination = new DestinationOption();
        @Mixin public MetadataOption metadata = new MetadataOption();

        @Override
        public Integer call() {
            Options opts = new Options();
            applyAlgorithmSource(
                    opts,
                    new OfflineAlgorithmSource.Direct(
                            algo.algorithmJar,
                            algo.algorithmDefinition,
                            List.of(),
                            algo.additionalJarFiles,
                            null));
            opts.sourceFiles = sources.sourceFiles.files;
            opts.destinationFile = destination.destinationFile;
            opts.metadataLocation = metadata.metadataLocation;
            return runTask("generate-state", opts);
        }
    }

    @Command(name = "performance-test", description = "Perform a performance test.", mixinStandardHelpOptions = true)
    public static final class PerformanceTestCommand implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @ArgGroup(exclusive = true, multiplicity = "1")
        public ComposedAlgorithmSourceOptions algorithmSource;
        @Mixin public DomainModelJarOptions domainModelJars = new DomainModelJarOptions();
        @Mixin public ExecutionOptions execution = new ExecutionOptions();
        @Mixin public SourceFilesOption sources = new SourceFilesOption();
        @Mixin public MetadataOption metadata = new MetadataOption();

        @Option(
                names = {"--target-rps"},
                description = "Target requests/sec. When set (>0), overrides --target-throughput-fraction.",
                defaultValue = "-1"
        )
        public double targetRps = -1.0;

        @Option(
                names = {"--target-throughput-fraction"},
                description = "Fraction of warmup mean throughput to use as target requests/sec when --target-rps is not set. 0 disables pacing.",
                defaultValue = "0.8"
        )
        public double targetThroughputFraction = 0.8;

        @Option(
                names = {"--workload-mode"},
                description = "Execution workload mode for performance-test. Supported values: realtime, batch. Default: realtime."
        )
        public String workloadMode;

        @Option(
                names = {"--sample-pool-size"},
                description = "Number of decoded requests to keep in the in-memory sample pool before measured repeats. "
                        + "Defaults to min(--samples, 3000) when --samples is set, otherwise 3000.",
                defaultValue = "-1"
        )
        public int samplePoolSize = -1;

        @Override
        public Integer call() {
            Options opts = new Options();
            applyAlgorithmSource(
                    opts,
                    selectedComposedAlgorithmSource(spec, algorithmSource, domainModelJars.jars));
            applyExecutionOptions(opts, execution);
            opts.samplePoolSize = samplePoolSize;
            opts.sourceFiles = sources.sourceFiles.files;
            opts.metadataLocation = metadata.metadataLocation;
            opts.targetRps = targetRps;
            opts.targetThroughputFraction = targetThroughputFraction;
            opts.performanceTestWorkloadMode = workloadMode;
            return runTask("performance-test", opts);
        }
    }

}
