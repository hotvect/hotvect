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

public final class AlgorithmDefinitionReader {
    private final ProcessingContext context;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlgorithmDefinitionReader(ProcessingContext context) {
        this.context = context;
    }

    public List<String> readOutputFeatures(TypeElement specElement, String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) return Collections.emptyList();

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
            List<String> outputs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode node : featuresNode) {
                if (!node.isTextual()) {
                    context.messager().printMessage(Diagnostic.Kind.ERROR,
                            "Feature name must be a string in transformer_parameters.features: " + resourcePath,
                            specElement);
                    return null;
                }
                String name = node.asText();
                if (name.isBlank()) {
                    context.messager().printMessage(Diagnostic.Kind.ERROR,
                            "Feature name must be non-empty in transformer_parameters.features: " + resourcePath,
                            specElement);
                    return null;
                }
                if (seen.add(name)) {
                    outputs.add(name);
                } else {
                    context.messager().printMessage(Diagnostic.Kind.WARNING,
                            "Duplicate feature in transformer_parameters.features: " + name,
                            specElement);
                }
            }
            if (outputs.isEmpty()) {
                context.messager().printMessage(Diagnostic.Kind.ERROR,
                        "transformer_parameters.features is empty in algorithm definition: " + resourcePath,
                        specElement);
                return null;
            }
            return outputs;
        } catch (IOException ex) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to parse algorithm definition JSON: " + ex.getMessage(),
                    specElement);
            return null;
        }
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
