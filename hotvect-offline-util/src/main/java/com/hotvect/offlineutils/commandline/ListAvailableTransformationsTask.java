package com.hotvect.offlineutils.commandline;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.Beta;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.transformation.ranking.ComputingRankingTransformer;
import com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineSupporterFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Beta
public class ListAvailableTransformationsTask extends Task {

    protected ListAvailableTransformationsTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> perform() throws Exception {
        // TODO: Define a common interface for both ComputingRankingTransformer implementations
        // (com.hotvect.api.transformation.ranking.ComputingRankingTransformer and
        // com.hotvect.core.transform.ranking.ComputingRankingTransformer) to avoid reflection.
        // For now, we use reflection as a quick fix - this is acceptable for a utility that
        // needs to support both old and new transformer types without adding compile-time
        // dependencies on hotvect-core.

        AlgorithmOfflineSupporterFactory algorithmSupporterFactory = new AlgorithmOfflineSupporterFactory(this.offlineTaskContext.classLoader());

        Object featureExtractionDependency = algorithmSupporterFactory.getFeatureExtractionDependency(offlineTaskContext.algorithmDefinition(), this.offlineTaskContext.options().parameters);

        // Check if this is a ComputingRankingTransformer (old or new) using reflection
        if(isComputingRankingTransformer(featureExtractionDependency)){
            File dest = this.offlineTaskContext.options().destinationFile;
            List<?> transformationMetadata = getTransformationMetadataViaReflection(featureExtractionDependency);
            writeTransformationMetadata(dest, transformationMetadata);
        } else {
            throw new MalformedAlgorithmException("List available transformations is only supported for a ComputingRankingTransformer");
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("transformer", featureExtractionDependency.getClass().getCanonicalName());
        return metadata;
    }

    private boolean isComputingRankingTransformer(Object obj) {
        Class<?> clazz = obj.getClass();
        // Check for old api.transformation.ranking.ComputingRankingTransformer
        if (ComputingRankingTransformer.class.isAssignableFrom(clazz)) {
            return true;
        }
        // Check for new core.transform.ranking.ComputingRankingTransformer via class name
        for (Class<?> iface : clazz.getInterfaces()) {
            if ("com.hotvect.core.transform.ranking.ComputingRankingTransformer".equals(iface.getName())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<?> getTransformationMetadataViaReflection(Object transformer) throws Exception {
        Method method = transformer.getClass().getMethod("getTransformationMetadata");
        return (List<?>) method.invoke(transformer);
    }

    private void writeTransformationMetadata(File dest, List<?> transformationMetadata) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        if (this.offlineTaskContext.options().verbose){
            objectMapper.writeValue(dest, transformationMetadata);
        } else {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode transformations = objectMapper.createArrayNode();
            for (Object metadata : transformationMetadata) {
                Namespace namespace = getNamespaceViaReflection(metadata);
                ObjectNode node = objectMapper.createObjectNode();
                node.put("name", namespace.getName());

                // Add return_type_hint if present
                Class<?> returnTypeHint = namespace.getReturnTypeHint();
                if (returnTypeHint != null) {
                    node.put("return_type_hint", returnTypeHint.getName());
                } else {
                    node.putNull("return_type_hint");
                }

                // Add feature_value_type if present
                if (namespace.getFeatureValueType() != null) {
                    node.put("feature_value_type", namespace.getFeatureValueType().toString());
                } else {
                    node.putNull("feature_value_type");
                }

                transformations.add(node);
            }
            root.set("transformations", transformations);
            objectMapper.writeValue(dest, root);
        }
    }

    private Namespace getNamespaceViaReflection(Object metadata) throws Exception {
        Method method = metadata.getClass().getMethod("getNamespace");
        return (Namespace) method.invoke(metadata);
    }
}
