package com.eshioji.hotvect.onlineutils.hotdeploy;

import com.eshioji.hotvect.api.AlgorithmDefinition;
import com.eshioji.hotvect.api.ScorerFactory;
import com.eshioji.hotvect.api.VectorizerFactory;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.zip.ZipFile;

public class AlgorithmHolder<R> implements AutoCloseable {
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

    private AlgorithmDefinition readAlgorithmDefinition(URL urls) {
        try (ChildOnlyClassLoader loader = new ChildOnlyClassLoader(new URL[]{urls})) {
            String algoDefJson = CharStreams.toString(new InputStreamReader(loader.getResourceAsStream("algorithm_definition.json"), Charsets.UTF_8));
            return AlgorithmDefinition.parse(algoDefJson);
        } catch (IOException e) {
            throw new RuntimeException(e);
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

    public Scorer<R> load(File parameterFile) throws MalformedAlgorithmException {
        Vectorizer<R> vectorizer = getVectorizer(parameterFile);
        return getScorer(vectorizer, parameterFile);
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
}
