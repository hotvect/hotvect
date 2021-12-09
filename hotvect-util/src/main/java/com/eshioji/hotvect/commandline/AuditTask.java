//package com.eshioji.hotvect.commandline;
//
//import com.codahale.metrics.MetricRegistry;
//import com.eshioji.hotvect.api.AlgorithmDefinition;
//import com.eshioji.hotvect.api.codec.ExampleDecoder;
//import com.eshioji.hotvect.api.codec.ExampleEncoder;
//import com.eshioji.hotvect.core.audit.AuditableExampleEncoder;
//import com.eshioji.hotvect.core.audit.AuditableVectorizer;
//import com.eshioji.hotvect.core.audit.RawFeatureName;
//import com.eshioji.hotvect.export.AuditJsonEncoder;
//import com.eshioji.hotvect.util.CpuIntensiveFileAggregator;
//import com.eshioji.hotvect.util.CpuIntensiveFileMapper;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ConcurrentMap;
//import java.util.function.Function;
//import java.util.stream.Stream;
//
//import static com.google.common.base.Preconditions.checkState;
//
//public class AuditTask<R> extends Task<R> {
//    public AuditTask(Options opts, MetricRegistry metricRegistry, AlgorithmDefinition algorithmDefinition) throws Exception {
//        super(opts, metricRegistry, algorithmDefinition);
//    }
//
//    @Override
//    protected Map<String, String> perform() throws Exception {
//        ExampleDecoder<R> exampleDecoder = instantiate(super.algorithmDefinition.getExampleDecoderFactoryClassName());
//        ExampleEncoder<R> exampleEncoder = instantiate(super.algorithmDefinition.getExampleEncoderFactoryClassName());
//
//        checkState(exampleEncoder instanceof AuditableExampleEncoder,
//                "Encoder must implement " +AuditableExampleEncoder.class.getSimpleName() + " for audit task");
//
//        AuditableExampleEncoder<R> auditableExampleEncoder = (AuditableExampleEncoder<R>) exampleEncoder;
//        AuditableVectorizer<R> auditableVectorizer = auditableExampleEncoder.getVectorizer();
//
//        var auditEncoder = new AuditJsonEncoder<>(auditableVectorizer);
//
//        Function<String, Stream<String>> transformation = exampleDecoder.andThen(s -> s.map(auditEncoder));
//
//        var processor = CpuIntensiveFileMapper.mapper(metricRegistry, opts.sourceFile, opts.destinationFile, transformation);
//
//        processor.run();
//        Map<String, String> metadata = new HashMap<>();
//        metadata.put("example_decoder", exampleDecoder.getClass().getCanonicalName());
//        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());
//        return metadata;
//    }
//
//
//
//}