package com.hotvect.commandline;


import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.hotvect.api.codec.ExampleDecoder;
import com.hotvect.api.codec.ExampleEncoder;
import com.hotvect.core.util.ListTransform;
import com.hotvect.util.CpuIntensiveFileMapper;
import com.hotvect.onlineutils.hotdeploy.ZipFiles;
import com.google.common.collect.ImmutableMap;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

public class EncodeTask<R> extends Task<R> {

    protected EncodeTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, String> perform() throws Exception {
        ExampleDecoder<R> exampleDecoder = getTrainDecoder();

        ExampleEncoder<R> exampleEncoder;
        if (this.offlineTaskContext.getOptions().parameters != null){
            try(ZipFile parameterFile = new ZipFile(this.offlineTaskContext.getOptions().parameters)){
                Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
                var vectorizer = getVectorizer(parameters);
                exampleEncoder = getTrainEncoder(vectorizer);
            }
        } else {
            var vectorizer = getVectorizer(ImmutableMap.of());
            exampleEncoder = getTrainEncoder(vectorizer);
        }

        Function<String, List<String>> transformation = exampleDecoder.andThen(s -> ListTransform.map(s, exampleEncoder));

        CpuIntensiveFileMapper processor = CpuIntensiveFileMapper.mapper(this.offlineTaskContext.getMetricRegistry(), this.offlineTaskContext.getOptions().sourceFile, this.offlineTaskContext.getOptions().destinationFile, transformation);

        processor.run();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("example_decoder", exampleDecoder.getClass().getCanonicalName());
        metadata.put("example_encoder", exampleEncoder.getClass().getCanonicalName());

        Meter mainMeter = this.offlineTaskContext.getMetricRegistry().meter(MetricRegistry.name(CpuIntensiveFileMapper.class, "record"));
        metadata.put("mean_throughput", String.valueOf(mainMeter.getMeanRate()));
        metadata.put("total_record_count", String.valueOf(mainMeter.getCount()));


        return metadata;
    }



}
