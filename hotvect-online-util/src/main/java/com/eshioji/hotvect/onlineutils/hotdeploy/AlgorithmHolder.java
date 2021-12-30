package com.eshioji.hotvect.onlineutils.hotdeploy;

import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.api.AlgorithmParameterMetadata;
import com.eshioji.hotvect.api.ScorerFactory;
import com.eshioji.hotvect.api.VectorizerFactory;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.zip.ZipFile;

import static com.google.common.base.Preconditions.checkNotNull;

public class AlgorithmHolder<R> implements AutoCloseable {
    private static final ObjectMapper OM = new ObjectMapper();
    private final File algorithmJar;
    private final URLClassLoader classLoader;
    private final AlgorithmDefinition algorithmDefinition;
    private final VectorizerFactory<R> vectorizerFactory;
    private final ScorerFactory<R> scorerFactory;

    public AlgorithmHolder(File algorithmJar) {
        try {
            this.algorithmJar = algorithmJar;
            URL algorithmJarUrl = algorithmJar.toURI().toURL();
            this.classLoader = new URLClassLoader(new URL[]{algorithmJarUrl});
            this.algorithmDefinition = readAlgorithmDefinition(algorithmJarUrl);
            this.vectorizerFactory = (VectorizerFactory<R>) Class.forName(algorithmDefinition.getVectorizerFactoryName(), true, this.classLoader).getDeclaredConstructor().newInstance();
            this.scorerFactory = (ScorerFactory<R>) Class.forName(algorithmDefinition.getScorerFactoryName(), true, this.classLoader).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    /**
     * Closes the associated classloader so that no more new classes can be loaded from it
     * It also closes and removes the jarfile that was downloaded.
     * However, it does not unload the class definitions that were loaded through the classloader.
     * These must be removed through a reboot
     *
     * @throws Exception
     */
    @Override
    public void close() throws Exception {
        this.classLoader.close();
    }

    public AlgorithmInstance<R> load(File parameterFile) throws MalformedAlgorithmException {
        AlgorithmParameterMetadata algorithmParameterMetadata = readAlgorithmParameterMetadata(parameterFile);
        Vectorizer<R> vectorizer = getVectorizer(parameterFile);
        Scorer<R> scorer = getScorer(vectorizer, parameterFile);
        if (!algorithmDefinition.getAlgorithmName().equals(algorithmParameterMetadata.getAlgorithmName())) {
            throw new MalformedAlgorithmException(
                    String.format(
                            "Algorithm name of supplied parameter does not match: expected %s but got %s",
                            algorithmDefinition.getAlgorithmName(), algorithmParameterMetadata.getAlgorithmName()
                    )
            );

        }
        return new AlgorithmInstance<>(algorithmDefinition, algorithmParameterMetadata, scorer);
    }

    private Scorer<R> getScorer(Vectorizer<R> vectorizer, File file) throws MalformedAlgorithmException {
        try (ZipFile parameterFile = new ZipFile(file)) {

            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
            return this.scorerFactory.apply(vectorizer, parameters);
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    private Vectorizer<R> getVectorizer(File file) throws MalformedAlgorithmException {
        try (ZipFile parameterFile = new ZipFile(file)) {
            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
            return this.vectorizerFactory.apply(algorithmDefinition.getVectorizerParameter(), parameters);
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    private AlgorithmParameterMetadata readAlgorithmParameterMetadata(File file) throws MalformedAlgorithmException {
        try (ZipFile parameterFile = new ZipFile(file)) {
            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);

            if (parameters.get("algorithm_parameters.json") == null) {
                throw new MalformedAlgorithmException("algorithm_parameters.json not found in the parameter package. Only found:" + parameters.keySet());
            }
            try(var reader = new BufferedReader(new InputStreamReader(parameters.get("algorithm_parameters.json"), Charsets.UTF_8))){
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
}
