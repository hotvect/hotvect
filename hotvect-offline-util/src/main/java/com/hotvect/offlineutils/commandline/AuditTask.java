package com.hotvect.offlineutils.commandline;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.audit.AuditableRankingVectorizer;
import com.hotvect.api.audit.AuditableScoringVectorizer;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.offlineutils.export.RankingAuditEncoder;
import com.hotvect.offlineutils.export.ScoringAuditEncoder;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.offlineutils.util.OrderedFileMapper;
import com.hotvect.utils.ListTransform;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

public class AuditTask<EXAMPLE extends Example, SUBJECT> extends Task {

    protected AuditTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> perform() throws Exception {
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.getClassLoader());

        ExampleDecoder<EXAMPLE> scoringExampleDecoder = algorithmSupporterFactory.getTrainDecoder(offlineTaskContext.getAlgorithmDefinition());

        SUBJECT subject = instantiateSubject(algorithmSupporterFactory, offlineTaskContext.getAlgorithmDefinition(), this.offlineTaskContext.getOptions().parameters);

        ExampleEncoder<EXAMPLE> exampleEncoder = instantiateAuditEncoder(algorithmSupporterFactory, subject);

        Function<String, List<String>> transformation = scoringExampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        checkState(
                this.offlineTaskContext.getOptions().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.getOptions().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for audit tasks"
        );

        OrderedFileMapper processor = OrderedFileMapper.mapper(
                this.offlineTaskContext.getMetricRegistry(),
                this.offlineTaskContext.getOptions().sourceFiles.values().iterator().next(),
                this.offlineTaskContext.getOptions().destinationFile,
                transformation,
                this.offlineTaskContext.getOptions().maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.getOptions().maxThreads,
                this.offlineTaskContext.getOptions().queueLength,
                this.offlineTaskContext.getOptions().batchSize,
                this.offlineTaskContext.getOptions().samples
        );

        Map<String, Object> metadata = processor.call();
        metadata.put("example_decoder", scoringExampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());

        return metadata;
    }

    private SUBJECT instantiateSubject(AlgorithmOfflineSupporterFactory algorithmSupporterFactory, AlgorithmDefinition algorithmDefinition, File parameters) throws Exception {
        return algorithmSupporterFactory.loadFeatureExtractionDependency(algorithmDefinition, parameters);
    }


    @SuppressWarnings("removal")
    private ExampleEncoder<EXAMPLE> instantiateAuditEncoder(AlgorithmOfflineSupporterFactory algorithmSupporterFactory, SUBJECT subject) throws Exception {
        if(subject instanceof RankingVectorizer){
            AuditableRankingVectorizer<?, ?> vec = (AuditableRankingVectorizer<?, ?>) subject;
            return new RankingAuditEncoder(vec, algorithmSupporterFactory.getRewardFunction(offlineTaskContext.getAlgorithmDefinition()));
        } else if (subject instanceof AuditableScoringVectorizer){
            AuditableScoringVectorizer<?> vec = (AuditableScoringVectorizer<?>) subject;
            return new ScoringAuditEncoder(vec, algorithmSupporterFactory.getRewardFunction(offlineTaskContext.getAlgorithmDefinition()));
        } else if (subject instanceof com.hotvect.api.algodefinition.ranking.RankingTransformer){
            return new com.hotvect.offlineutils.export.RankingTransformerAuditEncoder((com.hotvect.api.algodefinition.ranking.RankingTransformer) subject, algorithmSupporterFactory.getRewardFunction(offlineTaskContext.getAlgorithmDefinition()));
        } else {
            throw new UnsupportedOperationException("Unknown audit subject of type:" + subject.getClass().getCanonicalName());
        }
    }


}
