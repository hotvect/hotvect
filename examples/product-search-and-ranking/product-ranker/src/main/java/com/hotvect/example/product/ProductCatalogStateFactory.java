package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import com.hotvect.api.data.AvailableAction;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProductCatalogStateFactory implements NonCompositeStateFactory<ProductCatalogState> {
    static final String CATALOG_PARAMETER = "catalog.jsonl";

    @Override
    @SuppressWarnings("removal")
    public ProductCatalogState apply(Map<String, InputStream> parameters, Optional<JsonNode> configuration) {
        InputStream catalogStream = parameters.get(CATALOG_PARAMETER);
        if (catalogStream == null) {
            throw new IllegalArgumentException(
                    "Missing " + CATALOG_PARAMETER + "; available parameters: " + parameters.keySet()
            );
        }
        List<AvailableAction<Product>> products = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(catalogStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    throw new IllegalArgumentException(CATALOG_PARAMETER + " contains a blank row at line " + lineNumber);
                }
                ProductCatalogJson.Entry entry = ProductCatalogJson.decode(line);
                products.add(AvailableAction.of(
                        entry.actionId(),
                        entry.product(),
                        Map.of("action_name", entry.product().title())
                ));
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not load " + CATALOG_PARAMETER, error);
        }
        return new ProductCatalogState(products);
    }
}
