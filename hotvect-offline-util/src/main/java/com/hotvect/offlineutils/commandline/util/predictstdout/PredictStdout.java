package com.hotvect.offlineutils.commandline.util.predictstdout;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.*;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.offlineutils.commandline.CommandlineUtility;
import com.hotvect.offlineutils.export.*;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.utils.AlgorithmDefinitionReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.function.Function;

public class PredictStdout {
    private static final Logger LOGGER = LoggerFactory.getLogger(PredictStdout.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        PredictStdoutOptions opts = new PredictStdoutOptions();
        CommandLine cmd = new CommandLine(opts);
        cmd.parseArgs(args);
        if (opts.helpRequested) {
            cmd.usage(System.out);
            return;
        }

        // Expand tildes in file paths
        CommandlineUtility.expandTildaOnFileFields(opts);

        // Configure logger
        File logFileLocation = opts.logFile;
        // Use the logFile from options
        configureLogFile(logFileLocation);
        LOGGER.info("Options: {}", opts);

        // Validate options
        if (!opts.algorithmJar.exists() || !opts.algorithmJar.isFile()) {
            System.err.println("Specified algorithm jar does not exist or is not a file: " + opts.algorithmJar.getAbsolutePath());
            System.exit(1);
        }
        if (opts.parameters != null && (!opts.parameters.exists() || !opts.parameters.isFile())) {
            System.err.println("Specified parameters file does not exist or is not a file: " + opts.parameters.getAbsolutePath());
            System.exit(1);
        }

        // Set up ClassLoader
        URL algoJarURL = opts.algorithmJar.toURI().toURL();
        URLClassLoader algoClassLoader = new URLClassLoader(new URL[]{algoJarURL}, PredictStdout.class.getClassLoader());

        // Read AlgorithmDefinition
        AlgorithmDefinition algorithmDefinition;
        if (opts.algorithmDefinition.toLowerCase(Locale.ROOT).endsWith(".json")) {
            // Read from JSON file
            File algorithmDefinitionFile = new File(opts.algorithmDefinition);
            if (!algorithmDefinitionFile.exists()) {
                System.err.println("Algorithm definition file does not exist: " + algorithmDefinitionFile.getAbsolutePath());
                System.exit(1);
            }
            String jsonContent = Files.asCharSource(algorithmDefinitionFile, Charsets.UTF_8).read();
            algorithmDefinition = new AlgorithmDefinitionReader().parse(jsonContent);
        } else {
            // Read algorithm definition from the jar
            algorithmDefinition = AlgorithmUtils.readAlgorithmDefinitionFromClassLoader(opts.algorithmDefinition, algoClassLoader);
        }
        LOGGER.info("Algorithm Definition: {}", algorithmDefinition);

        // Load AlgorithmInstance
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(algoClassLoader);
        AlgorithmInstanceFactory algorithmInstanceFactory = new AlgorithmInstanceFactory(algoClassLoader, false);
        AlgorithmInstance<?> algorithmInstance = algorithmInstanceFactory.load(algorithmDefinition, opts.parameters);
        LOGGER.info("Loaded AlgorithmInstance: {}", algorithmInstance);

        // Get ExampleDecoder
        ExampleDecoder<?> testDecoder = algorithmSupporterFactory.getTestDecoder(algorithmDefinition);

        // Get RewardFunction
        RewardFunction<?> rewardFunction = algorithmSupporterFactory.getRewardFunction(algorithmDefinition);

        // Create output formatter
        Function<Example, String> algorithmOutputFormatter = getOutputFormatter(algorithmInstance.getAlgorithm(), rewardFunction);

        // Create transformation function
        Function<String, String> transformation = s -> {
            try {
                List<?> decodedList = testDecoder.apply(s);
                if (decodedList == null || decodedList.isEmpty()) {
                    throw new Exception("Decoded example list is null or empty for input: " + s);
                }
                if (decodedList.size() > 1) {
                    throw new Exception("Expected a single example, but got multiple examples for input: " + s);
                }
                Object decoded = decodedList.get(0);
                String output = algorithmOutputFormatter.apply((Example) decoded);
                return output;
            } catch (Exception e) {
                throw new RuntimeException("Error during transformation: " + e.getMessage(), e);
            }
        };

        LOGGER.info("Ready to process input. Waiting for JSON input on stdin...");

        // Read lines from stdin and process
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = stdin.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                String output = transformation.apply(line);
                if (output != null) {
                    System.out.println(output);
                } else {
                    // Should not reach here, as null outputs are treated as errors
                    LOGGER.error("Output is null for input: {}", line);
                    printErrorJson("Output is null for input", line, null);
                }
            } catch (Exception e) {
                LOGGER.error("Error processing input: {}", e.getMessage(), e);
                printErrorJson(e.getMessage(), line, e);
            }
        }
    }

    private static Function<Example, String> getOutputFormatter(Algorithm algo, RewardFunction<?> rewardFunction) throws MalformedAlgorithmException {
        if (algo instanceof Scorer) {
            Scorer<?> scorer = (Scorer<?>) algo;
            return new ScoringResultFormatter().apply(rewardFunction, scorer);
        } else if (algo instanceof Ranker) {
            Ranker<?, ?> ranker = (Ranker<?, ?>) algo;
            return new RankingResultFormatter().apply(rewardFunction, ranker);
        } else if (algo instanceof BulkScorer) {
            BulkScorer<?, ?> bulkScorer = (BulkScorer<?, ?>) algo;
            Ranker<?, ?> ranker = new BulkScoreGreedyRanker(bulkScorer);
            return new RankingResultFormatter().apply(rewardFunction, ranker);
        } else if (algo instanceof TopK) {
            TopK topK = (TopK) algo;
            return new TopKResultFormatter().apply(rewardFunction, topK);
        } else {
            throw new MalformedAlgorithmException("Unknown algorithm type: " + algo.getClass().getCanonicalName());
        }
    }

    private static void configureLogFile(File logfile) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        // Remove existing appenders attached to root logger
        ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.detachAndStopAllAppenders();

        FileAppender fileAppender = new FileAppender();
        fileAppender.setContext(loggerContext);
        fileAppender.setName("logfile");
        fileAppender.setFile(logfile.getAbsolutePath());

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%d{YYYY-MM-dd HH:mm:ss} %thread %level - %msg%n");
        encoder.start();

        fileAppender.setEncoder(encoder);
        fileAppender.start();

        // Attach the file appender to the root logger
        rootLogger.addAppender(fileAppender);

        // Optionally set the logger level to INFO
        rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        // Ensure that the logger does not log to stdout
        // We have already detached all appenders
    }

    private static void printErrorJson(String errorMessage, String inputLine, Exception exception) {
        try {
            ErrorJson errorJson = new ErrorJson();
            errorJson.error = new ErrorDetail();
            errorJson.error.message = errorMessage;
            errorJson.error.input = inputLine;
            if (exception != null) {
                StringWriter sw = new StringWriter();
                exception.printStackTrace(new PrintWriter(sw));
                errorJson.error.stacktrace = sw.toString();
            }
            String jsonOutput = OBJECT_MAPPER.writeValueAsString(errorJson);
            System.out.println(jsonOutput);
        } catch (JsonProcessingException e) {
            // If we fail to serialize the error JSON, print a plain message
            LOGGER.error("Failed to serialize error JSON: {}", e.getMessage(), e);
            System.out.println("{\"error\": \"Failed to serialize error JSON\"}");
        }
    }

    // Inner classes to represent the error JSON
    private static class ErrorJson {
        public ErrorDetail error;
    }

    private static class ErrorDetail {
        public String message;
        public String input;
        public String stacktrace;
    }
}