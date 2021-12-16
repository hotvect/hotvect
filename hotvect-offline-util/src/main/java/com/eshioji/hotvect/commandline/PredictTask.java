package com.eshioji.hotvect.commandline;


import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.api.scoring.Scorer;
import com.eshioji.hotvect.core.util.ListTransform;
import com.eshioji.hotvect.util.CpuIntensiveFileMapper;
import com.eshioji.hotvect.onlineutils.hotdeploy.ZipFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

public class PredictTask<R> extends Task<R> {
    private static final Logger logger = LoggerFactory.getLogger(PredictTask.class);

    protected PredictTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }


    @Override
    protected Map<String, String> perform() throws Exception {
        ExampleDecoder<R> exampleDecoder = getPredictDecoder();

        Scorer<R> scorer;
        try(ZipFile parameterFile = new ZipFile(super.offlineTaskContext.getOptions().parameters)){
            Map<String, InputStream> parameters = ZipFiles.parameters(parameterFile);
            logger.info("Parameters loaded {}",parameters.keySet());
            scorer = getScorer(parameters);
        }

        Function<Example<R>, String> scoreOutputFormatter = x ->
                scorer.applyAsDouble(x.getRecord()) + "," + x.getTarget();

        Function<String, List<String>> transformation =
                exampleDecoder.andThen(i -> ListTransform.map(i, scoreOutputFormatter));

        CpuIntensiveFileMapper processor = CpuIntensiveFileMapper.mapper(
                super.offlineTaskContext.getMetricRegistry(),
                super.offlineTaskContext.getOptions().sourceFile,
                super.offlineTaskContext.getOptions().destinationFile, transformation);
        processor.run();

        Map<String, String> metadata = new HashMap<>();
        Meter mainMeter = super.offlineTaskContext.getMetricRegistry().meter(MetricRegistry.name(CpuIntensiveFileMapper.class, "record"));
        metadata.put("mean_throughput", String.valueOf(mainMeter.getMeanRate()));
        metadata.put("total_record_count", String.valueOf(mainMeter.getCount()));

        return metadata;

    }

}
