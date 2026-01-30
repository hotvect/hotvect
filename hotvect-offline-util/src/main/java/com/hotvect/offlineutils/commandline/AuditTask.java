package com.hotvect.offlineutils.commandline;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.audit.AuditableRankingVectorizer;
import com.hotvect.api.codec.common.ExampleDecoder;
import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;
import com.hotvect.offlineutils.export.RankingAuditEncoder;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.concurrency.fileutils.OrderedFileMapper;
import com.hotvect.utils.ListTransform;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

public class AuditTask<EXAMPLE extends Example<? extends OfflineRequest, ?>, SUBJECT> extends Task {

    protected AuditTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> perform() throws Exception {
        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());

        ExampleDecoder<EXAMPLE> scoringExampleDecoder = algorithmSupporterFactory.getTrainDecoder(offlineTaskContext.algorithmDefinition());

        SUBJECT subject = instantiateSubject(algorithmSupporterFactory, offlineTaskContext.algorithmDefinition(), this.offlineTaskContext.options().parameters);

        ExampleEncoder<EXAMPLE> exampleEncoder = instantiateAuditEncoder(algorithmSupporterFactory, subject);

        Function<String, List<String>> transformation = scoringExampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        checkState(
                this.offlineTaskContext.options().sourceFiles.size() == 1 &&
                        this.offlineTaskContext.options().sourceFiles.keySet().iterator().next().equals("default")
                ,
                "Only one source file type is supported for audit tasks"
        );

        OrderedFileMapper processor = OrderedFileMapper.mapper(
                this.offlineTaskContext.meterRegistry(),
                this.offlineTaskContext.options().sourceFiles.values().iterator().next(),
                this.offlineTaskContext.options().destinationFile,
                transformation,
                this.offlineTaskContext.options().maxThreads < 0 ? max(Runtime.getRuntime().availableProcessors() - 1, 1) : this.offlineTaskContext.options().maxThreads,
                this.offlineTaskContext.options().queueLength,
                this.offlineTaskContext.options().batchSize,
                this.offlineTaskContext.options().samples
        );

        Map<String, Object> metadata = processor.call();
        metadata.put("example_decoder", scoringExampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());

        return metadata;
    }

    private SUBJECT instantiateSubject(AlgorithmOfflineSupporterFactory algorithmSupporterFactory, AlgorithmDefinition algorithmDefinition, File parameters) throws Exception {
        return algorithmSupporterFactory.loadFeatureExtractionDependency(algorithmDefinition, parameters, Map.of());
    }


    @SuppressWarnings("removal")
    private ExampleEncoder<EXAMPLE> instantiateAuditEncoder(AlgorithmOfflineSupporterFactory algorithmSupporterFactory, SUBJECT subject) throws Exception {
        if(subject instanceof RankingVectorizer){
            AuditableRankingVectorizer<?, ?> vec = (AuditableRankingVectorizer<?, ?>) subject;
            return new RankingAuditEncoder(vec, algorithmSupporterFactory.getRewardFunction(offlineTaskContext.algorithmDefinition()));
        } else if (subject instanceof com.hotvect.api.algodefinition.ranking.RankingTransformer){
            return new com.hotvect.offlineutils.export.RankingTransformerAuditEncoder((com.hotvect.api.algodefinition.ranking.RankingTransformer) subject, algorithmSupporterFactory.getRewardFunction(offlineTaskContext.algorithmDefinition()));
        } else {
            throw new UnsupportedOperationException("Unknown audit subject of type:" + subject.getClass().getCanonicalName());
        }
    }


}
