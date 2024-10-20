package com.hotvect.vw.audit;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.audit.AuditableRankingVectorizer;
import com.hotvect.api.audit.RawFeatureName;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.state.StateGenerator;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.offlineutils.util.CpuIntensiveFileAggregator;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.vw.VwModelImporter;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.*;
import java.util.List;
import java.util.Map;

import static java.lang.Math.max;

public class VwAuditStateGenerator implements StateGenerator<VwAuditState> {
    private final AlgorithmDefinition algorithmDefinition;
    private final AlgorithmOfflineSupporterFactory algorithmSupporterFactory;

    public VwAuditStateGenerator(AlgorithmDefinition algorithmDefinition, ClassLoader classLoader) throws MalformedAlgorithmException {
        this.algorithmDefinition = algorithmDefinition;
        this.algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(classLoader);
    }

    @Override
    public VwAuditState apply(Map<String, List<File>> sourceFileMap) {
        List<File> sourceFiles = sourceFileMap.get("default");
        if (sourceFiles.size() != 1 || !Files.getFileExtension(sourceFiles.get(0).getAbsolutePath()).equalsIgnoreCase("zip")) {
            throw new IllegalArgumentException(this.getClass().getSimpleName() + " only supports hotvect-vw parameter zip package as source file. Got instead:" + sourceFiles);
        }
        File parameterFile = sourceFiles.get(0);
        Int2DoubleMap parameters = readVwParameters(parameterFile);
        VwAuditState state = new VwAuditState(parameters);


        try {
            RankingExampleDecoder decoder = (RankingExampleDecoder) algorithmSupporterFactory.getTestDecoder(this.algorithmDefinition);
            AuditableRankingVectorizer<?, ?> auditableRankingVectorizer = algorithmSupporterFactory.loadFeatureExtractionDependency(this.algorithmDefinition, parameterFile);
            ThreadLocal<Map<Integer, List<RawFeatureName>>> auditState = auditableRankingVectorizer.enableAudit();

            MetricRegistry metricRegistry = new MetricRegistry();
            VwAuditState ret = CpuIntensiveFileAggregator.aggregator(
                    metricRegistry,
                    sourceFileMap.get("training_data"),
                    () -> state,
                    (vwAuditState, s) -> {
                        List<RankingExample> decoded = decoder.apply(s);
                        for (RankingExample rankingExample : decoded) {
                            auditableRankingVectorizer.apply(rankingExample.getRankingRequest());
                            Map<Integer, List<RawFeatureName>> dictionary = auditState.get();
                            for (Map.Entry<Integer, List<RawFeatureName>> e : dictionary.entrySet()) {
                                VwAuditRecord vwAuditRecord = state.getState().get(e.getKey().intValue());
                                if(vwAuditRecord != null){
                                    vwAuditRecord.getRawFeatureNames().add(e.getValue());
                                } else {
                                    // This feature is not used, ignore
                                }

                            }
                        }
                        return state;
                    },
                    max(Runtime.getRuntime().availableProcessors() - 1, 1),
                    10,
                    200
            ).call();
            Slf4jReporter.forRegistry(metricRegistry).build().report();
            return ret;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Int2DoubleMap readVwParameters(File parameterFile) {
        try (ZipFile file = new ZipFile(parameterFile)) {
            Map<String, InputStream> parameters = AlgorithmUtils.extractParameters(algorithmDefinition.getAlgorithmId(), file);
            return new VwModelImporter().apply(new BufferedReader(new InputStreamReader(parameters.get("model.parameter"), Charsets.UTF_8)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
