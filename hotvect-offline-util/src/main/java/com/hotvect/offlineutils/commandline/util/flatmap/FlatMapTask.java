package com.hotvect.offlineutils.commandline.util.flatmap;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.offlineutils.commandline.CommandlineUtility;
import com.hotvect.offlineutils.util.OrderedFileMapper;
import com.hotvect.utils.VerboseCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static java.lang.Math.max;

public class FlatMapTask extends VerboseCallable<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(FlatMapTask.class);
    private final MetricRegistry metricRegistry;
    private final FlatMapOptions flatMapOptions;

    public FlatMapTask(MetricRegistry metricRegistry, FlatMapOptions opts) {
        this.metricRegistry = metricRegistry;
        this.flatMapOptions = opts;
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        log.info("Flatmap source file: {} to destination file: {} using flatmap fun: {}", this.flatMapOptions.sourceFile, this.flatMapOptions.destinationFile, this.flatMapOptions.flatmapFunFactoryClassname);
        URL[] urls = this.flatMapOptions.jars.stream().map(FlatMapTask::toURL).toArray(URL[]::new);


        URLClassLoader classLoader = new URLClassLoader(urls);
        FlatMapFunFactory flatMapFunFactory = instantiate(classLoader, this.flatMapOptions.flatmapFunFactoryClassname);

        Optional<JsonNode> hyperparameter = CommandlineUtility.parseStringOrFileToJsonNode(this.flatMapOptions.hyperParameter);


        Function<String, List<String>> flatMap = flatMapFunFactory.apply(hyperparameter);

        OrderedFileMapper processor = OrderedFileMapper.mapper(
                this.metricRegistry,
                this.flatMapOptions.sourceFile,
                this.flatMapOptions.destinationFile,
                flatMap,
                this.flatMapOptions.maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : flatMapOptions.maxThreads,
                this.flatMapOptions.queueLength,
                this.flatMapOptions.batchSize,
                this.flatMapOptions.samples
        );

        Map<String, Object> metadata = processor.call();
        metadata.put("flatmap_function", flatMapOptions.flatmapFunFactoryClassname);
        log.info("Finished flatmap task: {}", metadata);

        return metadata;
    }
    private static URL toURL(File file) {
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private FlatMapFunFactory instantiate(URLClassLoader classLoader, String flatMapFunctionFactory) {
        try {
            Class<?> clazz = classLoader.loadClass(flatMapFunctionFactory);
            return (FlatMapFunFactory) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
