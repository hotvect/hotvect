package com.hotvect.core.annotation.processor.scan;

import java.io.IOException;
import java.util.*;

import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.core.annotation.processor.ProcessingContext;
import com.hotvect.core.annotation.processor.model.FeatureSpec;

public final class AlgorithmDefinitionReader {
    private final ProcessingContext context;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlgorithmDefinitionReader(ProcessingContext context) {
        this.context = context;
    }

    public List<FeatureSpec> readOutputFeatures(TypeElement specElement, String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "algorithmDefinitionResource is required on @GenerateSimpleRankingTransformer.",
                    specElement);
            return null;
        }

        String normalized = normalize(resourcePath);
        String json = readResource(specElement, normalized);
        if (json == null) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Unable to read algorithm definition resource: " + resourcePath, specElement);
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode featuresNode = root.path("transformer_parameters").path("features");
            if (!featuresNode.isArray()) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "Algorithm definition does not contain transformer_parameters.features array: " + resourcePath,
                        specElement);
                return null;
            }
            List<FeatureSpec> features = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode node : featuresNode) {
                FeatureSpec feature = readFeatureSpec(specElement, node, resourcePath);
                if (feature == null) {
                    return null;
                }
                if (seen.add(feature.name())) {
                    features.add(feature);
                } else {
                    context.messager().printMessage(Diagnostic.Kind.WARNING,
                            "Duplicate feature in transformer_parameters.features: " + feature.name(),
                            specElement);
                }
            }
            if (features.isEmpty()) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "transformer_parameters.features is empty in algorithm definition: " + resourcePath,
                        specElement);
                return null;
            }
            return features;
        } catch (IOException ex) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to parse algorithm definition JSON: " + ex.getMessage(),
                    specElement);
            return null;
        }
    }

    private FeatureSpec readFeatureSpec(TypeElement specElement, JsonNode node, String resourcePath) {
        if (node.isTextual()) {
            String name = node.asText().trim();
            if (name.isBlank()) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "Feature name must be non-empty in transformer_parameters.features: " + resourcePath,
                        specElement);
                return null;
            }
            return new FeatureSpec(name, null);
        }
        if (!node.isObject()) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Feature entry must be a string or object in transformer_parameters.features: " + resourcePath,
                    specElement);
            return null;
        }

        JsonNode nameNode = node.path("name");
        if (!nameNode.isTextual() || nameNode.asText().isBlank()) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Feature object must contain non-empty name in transformer_parameters.features: " + resourcePath,
                    specElement);
            return null;
        }

        String type = null;
        JsonNode typeNode = node.path("type");
        if (!typeNode.isMissingNode()) {
            if (!typeNode.isTextual() || typeNode.asText().isBlank()) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "Feature object type must be a non-empty string in transformer_parameters.features: " + resourcePath,
                        specElement);
                return null;
            }
            type = typeNode.asText().trim();
        }

        return new FeatureSpec(nameNode.asText().trim(), type);
    }

    private String readResource(TypeElement specElement, String resourcePath) {
        for (StandardLocation location : new StandardLocation[] {
                StandardLocation.SOURCE_PATH,
                StandardLocation.CLASS_PATH,
                StandardLocation.CLASS_OUTPUT
        }) {
            try {
                FileObject file = context.filer().getResource(location, "", resourcePath);
                CharSequence content = file.getCharContent(true);
                if (content != null) {
                    return content.toString();
                }
            } catch (IOException ex) {
                // Keep trying other locations.
            }
        }
        return null;
    }

    private String normalize(String path) {
        String trimmed = path.trim();
        if (trimmed.startsWith("/")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }
}
