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
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
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
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
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

    @Command(
            name = "hotvect-offline-util",
            description = "Hotvect offline utility.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    EncodeCommand.class,
                    PredictCommand.class,
                    AuditCommand.class,
                    GenerateStateCommand.class,
                    PerformanceTestCommand.class
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
        try {
            CommandlineUtility.expandTildaOnFileFields(opts);
            if (opts.metadataLocation.getName().endsWith(".json")) {
                throw new IllegalArgumentException("--metadata-path now expects a directory, not a .json file. Example: --metadata-path myrun.metadata");
            }
            File metadataDir = CommandlineUtility.ensureDirectoryExists(opts.metadataLocation, "--metadata-path");
            File logFileLocation = CommandlineUtility.logFile(metadataDir);
            configureLogFile(logFileLocation);

            reporter.start(20, TimeUnit.SECONDS);
            OfflineTaskContext offlineTaskContext = getOfflineTaskContext(taskName, opts);
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
            LOGGER.info("Wrote metadata: location={}, metadata={}", metadataFile, metadata);
            return 0;

        } catch (Throwable e) {
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
        }
    }

    private static OfflineTaskContext getOfflineTaskContext(String taskName, Options opts) throws IOException, MalformedAlgorithmException {
        checkArgument(opts.algorithmJar.exists() && opts.algorithmJar.isFile(), "Specified algorithm jar does not exist or is not a file: %s", opts.algorithmJar.getAbsolutePath());
        URL algoJarLocation = opts.algorithmJar.toURI().toURL();

        List<URL> classPath  = new ArrayList<>();
        if(!opts.additionalJarFiles.isEmpty()){
            LOGGER.info("Additional jar files specified:{}", opts.additionalJarFiles);
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

        return new OfflineTaskContext(algoClassLoader, METER_REGISTRY, opts, algorithmDefinition.get());
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
        @Mixin public SourceFilesOption sources = new SourceFilesOption();
        @Mixin public DestinationOption destination = new DestinationOption();
        @Mixin public MetadataOption metadata = new MetadataOption();

        @Option(names = {"--dest-schema-description"}, paramLabel = "DEST_SCHEMA_DESCRIPTION_FILE", description = "The file to which the schema description of the destination file will be written.")
        public File schemaDescriptionFile;

        @Override
        public Integer call() {
            validateOutputOrderingOptions(spec, ordering.ordered, ordering.unordered, ordering.writerNumShards);
            Options opts = new Options();
            opts.algorithmJar = algo.algorithmJar;
            opts.algorithmDefinition = algo.algorithmDefinition;
            opts.additionalJarFiles = algo.additionalJarFiles;
            opts.parameters = parameters.parameters;
            applyExecutionOptions(opts, execution);
            opts.sourceFiles = sources.sourceFiles.files;
            opts.destinationFile = destination.destinationFile;
            opts.metadataLocation = metadata.metadataLocation;
            applyOutputOrderingOptions(opts, ordering);
            opts.schemaDescriptionFile = schemaDescriptionFile;
            return runTask("encode", opts);
        }
    }

    @Command(name = "predict", description = "Perform prediction (test) on the source file.", mixinStandardHelpOptions = true)
    public static final class PredictCommand implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @Mixin public AlgorithmJarAndDefinitionOptions algo = new AlgorithmJarAndDefinitionOptions();
        @Mixin public OptionalParametersOption parameters = new OptionalParametersOption();
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

        @Override
        public Integer call() {
            validateOutputOrderingOptions(spec, ordering.ordered, ordering.unordered, ordering.writerNumShards);
            Options opts = new Options();
            opts.algorithmJar = algo.algorithmJar;
            opts.algorithmDefinition = algo.algorithmDefinition;
            opts.additionalJarFiles = algo.additionalJarFiles;
            opts.parameters = parameters.parameters;
            applyExecutionOptions(opts, execution);
            opts.sourceFiles = sources.sourceFiles.files;
            opts.destinationFile = destination.destinationFile;
            opts.metadataLocation = metadata.metadataLocation;
            applyOutputOrderingOptions(opts, ordering);
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
            opts.algorithmJar = algo.algorithmJar;
            opts.algorithmDefinition = algo.algorithmDefinition;
            opts.additionalJarFiles = algo.additionalJarFiles;
            opts.parameters = parameters.parameters;
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
            opts.algorithmJar = algo.algorithmJar;
            opts.algorithmDefinition = algo.algorithmDefinition;
            opts.additionalJarFiles = algo.additionalJarFiles;
            opts.sourceFiles = sources.sourceFiles.files;
            opts.destinationFile = destination.destinationFile;
            opts.metadataLocation = metadata.metadataLocation;
            return runTask("generate-state", opts);
        }
    }

    @Command(name = "performance-test", description = "Perform a performance test.", mixinStandardHelpOptions = true)
    public static final class PerformanceTestCommand implements Callable<Integer> {
        @Mixin public AlgorithmJarAndDefinitionOptions algo = new AlgorithmJarAndDefinitionOptions();
        @Mixin public ParametersOption parameters = new ParametersOption();
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
            opts.algorithmJar = algo.algorithmJar;
            opts.algorithmDefinition = algo.algorithmDefinition;
            opts.additionalJarFiles = algo.additionalJarFiles;
            opts.parameters = parameters.parameters;
            opts.maxThreads = execution.maxThreads;
            opts.batchSize = execution.batchSize;
            opts.queueLength = execution.queueLength;
            opts.readQueueLength = execution.readQueueLength;
            opts.writeQueueLength = execution.writeQueueLength;
            opts.samples = execution.samples;
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
