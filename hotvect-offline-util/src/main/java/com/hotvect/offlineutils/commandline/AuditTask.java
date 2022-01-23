package com.hotvect.offlineutils.commandline;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.vectorization.RankingVectorizer;
import com.hotvect.api.vectorization.Vectorizer;
import com.hotvect.core.audit.AuditableRankingVectorizer;
import com.hotvect.core.audit.AuditableScoringVectorizer;
import com.hotvect.core.util.ListTransform;
import com.hotvect.offlineutils.export.RankingAuditEncoder;
import com.hotvect.offlineutils.export.ScoringAuditEncoder;
import com.hotvect.offlineutils.util.CpuIntensiveFileMapper;
import com.hotvect.onlineutils.hotdeploy.util.ZipFiles;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

public class AuditTask<EXAMPLE extends Example, ALGO extends Algorithm, OUTCOME, VEC extends Vectorizer> extends Task<EXAMPLE, ALGO, OUTCOME> {

    protected AuditTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        ExampleDecoder<EXAMPLE> scoringExampleDecoder = getTrainDecoder();

        ExampleEncoder<EXAMPLE> exampleEncoder = getAuditJsonEncoder();

        Function<String, List<String>> transformation = scoringExampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        CpuIntensiveFileMapper processor = CpuIntensiveFileMapper.mapper(
                this.offlineTaskContext.getMetricRegistry(),
                this.offlineTaskContext.getOptions().sourceFile,
                this.offlineTaskContext.getOptions().destinationFile,
                transformation
        );

        processor.run();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("example_decoder", scoringExampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());

        Meter mainMeter = this.offlineTaskContext.getMetricRegistry().meter(MetricRegistry.name(CpuIntensiveFileMapper.class, "record"));
        metadata.put("mean_throughput", String.valueOf(mainMeter.getMeanRate()));
        metadata.put("total_record_count", String.valueOf(mainMeter.getCount()));


        return metadata;
    }

    private ExampleEncoder<EXAMPLE> getAuditJsonEncoder() throws Exception {
        if (this.offlineTaskContext.getOptions().parameters != null) {
            try (ZipFile parameterFile = new ZipFile(this.offlineTaskContext.getOptions().parameters)) {
                Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
                VEC vectorizer = getVectorizer(parameters);
                return instantiateAuditEncoder(vectorizer);
            }
        } else {
            VEC vectorizer = getVectorizer(ImmutableMap.of());
            return instantiateAuditEncoder(vectorizer);
        }
    }

    private ExampleEncoder<EXAMPLE> instantiateAuditEncoder(VEC vectorizer) throws Exception {
        if(vectorizer instanceof RankingVectorizer){
            AuditableRankingVectorizer<?, ?> vec = (AuditableRankingVectorizer<?, ?>) vectorizer;
            return new RankingAuditEncoder(vec, getRewardFunction());
        } else if (vectorizer instanceof AuditableScoringVectorizer){
            AuditableScoringVectorizer<?> vec = (AuditableScoringVectorizer<?>) vectorizer;
            return new ScoringAuditEncoder(vec, getRewardFunction());
        } else {
            throw new UnsupportedOperationException("Unknown vectorizer type:" + vectorizer.getClass().getCanonicalName());
        }
    }


}
