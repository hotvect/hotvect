package com.eshioji.hotvect.onlineutils.hotdeploy.common;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algodefinition.common.VectorizerFactory;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.vectorization.Vectorizer;
import com.eshioji.hotvect.onlineutils.hotdeploy.util.ChildOnlyClassLoader;
import com.eshioji.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.eshioji.hotvect.onlineutils.hotdeploy.util.ZipFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.zip.ZipFile;

import static com.google.common.base.Preconditions.checkNotNull;

public class AlgorithmHolder<ALGO extends Algorithm> implements AutoCloseable {
    private static final ObjectMapper OM = new ObjectMapper();
    protected final URLClassLoader classLoader;
    protected final AlgorithmDefinition algorithmDefinition;

    public AlgorithmHolder(File algorithmJar, ClassLoader parent) {
        try {
            URL algorithmJarUrl = algorithmJar.toURI().toURL();
            if (parent == null) {
                this.classLoader = new URLClassLoader(new URL[]{algorithmJarUrl});
            } else {
                this.classLoader = new URLClassLoader(new URL[]{algorithmJarUrl}, parent);
            }
            this.algorithmDefinition = readAlgorithmDefinition(algorithmJarUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public AlgorithmHolder(File algorithmJar) {
        this(algorithmJar, Thread.currentThread().getContextClassLoader());
    }

    public AlgorithmInstance<ALGO> load(File parameterFile) throws MalformedAlgorithmException {
        var parameterMetadata = readAlgorithmParameterMetadata(parameterFile);
        if (!algorithmDefinition.getAlgorithmName().equals(parameterMetadata.getAlgorithmName())) {
            throw new MalformedAlgorithmException(
                    String.format(
                            "Algorithm name of supplied parameter does not match: expected %s but got %s",
                            algorithmDefinition.getAlgorithmName(), parameterMetadata.getAlgorithmName()
                    )
            );

        }
        ALGO algorithm = this.getAlgorithm(this.getVectorizer(parameterFile), parameterFile);

        return new AlgorithmInstance<>(algorithmDefinition, parameterMetadata, algorithm);

    }

    /**
     * This method will not free any resources like loaded class definitions, underlying files etc.
     * It only prevents further attempt to load classes
     *
     * @throws Exception
     */
    @Override
    public void close() throws Exception {
        this.classLoader.close();
    }

    protected AlgorithmParameterMetadata readAlgorithmParameterMetadata(File file) throws MalformedAlgorithmException {
        try (ZipFile parameterFile = new ZipFile(file)) {
            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);

            if (parameters.get("algorithm_parameters.json") == null) {
                throw new MalformedAlgorithmException("algorithm_parameters.json not found in the parameter package. Only found:" + parameters.keySet());
            }
            try (var reader = new BufferedReader(new InputStreamReader(parameters.get("algorithm_parameters.json"), Charsets.UTF_8))) {
                JsonNode parsed = OM.readTree(reader);
                return new AlgorithmParameterMetadata(
                        checkNotNull(parsed.get("parameter_id").asText(), "parameter_id not found"),
                        checkNotNull(ZonedDateTime.parse(parsed.get("ran_at").asText()).toInstant(), "ran_at not found"),
                        checkNotNull(parsed.get("algorithm_name").asText(), "algorithm_name not found")
                );
            }
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    private AlgorithmDefinition readAlgorithmDefinition(URL urls) throws MalformedAlgorithmException {
        try (ChildOnlyClassLoader loader = new ChildOnlyClassLoader(new URL[]{urls})) {

            try (InputStream is = loader.getResourceAsStream("algorithm_definition.json")) {
                if (is == null) {
                    throw new MalformedAlgorithmException("algorithm_definition.json is not found in URL:" + urls);
                }

                String algoDefJson = CharStreams.toString(new InputStreamReader(is, Charsets.UTF_8));

                return AlgorithmDefinition.parse(algoDefJson);
            }
        } catch (IOException e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    protected final <V extends Vectorizer> ALGO getAlgorithm(V vectorizer, File parameterPackage) throws MalformedAlgorithmException {
        try (ZipFile parameterFile = new ZipFile(parameterPackage)) {
            AlgorithmFactory<V, ALGO> algorithmFactory = (AlgorithmFactory<V, ALGO>) Class.forName(algorithmDefinition.getAlgorithmFactoryName(), true, this.classLoader).getDeclaredConstructor().newInstance();
            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
            return algorithmFactory.apply(vectorizer, parameters);
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    protected final <V extends Vectorizer> V getVectorizer(File parameterPackage) throws MalformedAlgorithmException {
        try (ZipFile parameterFile = new ZipFile(parameterPackage)) {
            VectorizerFactory<V> vectorizerFactory = (VectorizerFactory<V>) Class.forName(algorithmDefinition.getVectorizerFactoryName(), true, this.classLoader).getDeclaredConstructor().newInstance();
            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
            return vectorizerFactory.apply(algorithmDefinition.getVectorizerParameter(), parameters);
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }


}