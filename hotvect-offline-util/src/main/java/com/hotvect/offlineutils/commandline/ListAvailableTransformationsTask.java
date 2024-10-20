//package com.hotvect.offlineutils.commandline;
//
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.PropertyNamingStrategies;
//import com.google.common.annotations.Beta;
//import com.hotvect.api.data.common.Example;
//import com.hotvect.api.transformation.memoization.TransformationMetadata;
//import com.hotvect.core.transform.ranking.MemoizingRankingTransformer;
//import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
//import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
//
//import java.io.File;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//@Beta
//public class ListAvailableTransformationsTask<EXAMPLE extends Example> extends Task {
//
//    protected ListAvailableTransformationsTask(OfflineTaskContext offlineTaskContext) {
//        super(offlineTaskContext);
//    }
//
//    @Override
//    protected Map<String, Object> perform() throws Exception {
//
//        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.getClassLoader());
//
//        Object featureExtractionDependency = algorithmSupporterFactory.getFeatureExtractionDependency(offlineTaskContext.getAlgorithmDefinition(), this.offlineTaskContext.getOptions().parameters);
//
//        if(featureExtractionDependency instanceof MemoizingRankingTransformer<?, ?>){
//            MemoizingRankingTransformer<?, ?> transformer = (MemoizingRankingTransformer<?, ?>) featureExtractionDependency;
//            File dest = this.offlineTaskContext.getOptions().destinationFile;
//            List<TransformationMetadata> transformationMetadata = transformer.getTransformationMetadata();
//            ObjectMapper objectMapper = new ObjectMapper();
//            objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
//            objectMapper.writeValue(dest, transformationMetadata);
//        } else {
//            throw new MalformedAlgorithmException("List available transformations is only supported for MemoizingRankingTransformer.");
//        }
//        Map<String, Object> metadata = new HashMap<>();
//        metadata.put("transformer", featureExtractionDependency.getClass().getCanonicalName());
//        return metadata;
//    }
//}
