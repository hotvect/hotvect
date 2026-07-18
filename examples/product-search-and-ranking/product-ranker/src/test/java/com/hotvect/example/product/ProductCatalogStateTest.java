package com.hotvect.example.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductCatalogStateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatorPackagesCatalogAndRuntimeRetrievesQueryMatches() throws Exception {
        Path source = temporaryDirectory.resolve("source/dt=2000-01-02");
        Files.createDirectories(source);
        Files.writeString(source.resolve("part-00000.jsonl"), """
                {"action_id":"tea","title":"Wooden tea set","category":"pretend-play","price":20.0,"popularity":0.5,"novelty":0.4}
                {"action_id":"helicopter","title":"Wooden toy helicopter","category":"vehicles","price":19.9,"popularity":0.7,"novelty":0.5}
                """);
        Path destination = temporaryDirectory.resolve("state");

        Map<String, Object> metadata = new ProductCatalogStateGenerator().apply(
                Map.of(ProductCatalogStateGenerator.SOURCE_NAME, List.of(source.toFile())),
                destination.toFile()
        );

        assertEquals(2, metadata.get("catalog_size"));
        ProductCatalogState state = new ProductCatalogStateFactory().apply(
                Map.of(
                        ProductCatalogStateFactory.CATALOG_PARAMETER,
                        Files.newInputStream(destination.resolve(ProductCatalogStateFactory.CATALOG_PARAMETER))
                ),
                Optional.empty()
        );
        var results = state.retrieve(new ProductQuery("toy helicopter", "vehicles", 25.0), 2);
        assertEquals(2, state.size());
        assertEquals("helicopter", results.getFirst().actionId());
        assertEquals(1, results.getFirst().additionalProperties().get("retrieval_rank"));
        assertEquals(ProductCatalogState.RETRIEVAL_POLICY, results.getFirst().additionalProperties().get("retrieval_policy"));
    }

    @Test
    void generatorRejectsDuplicateCatalogIds() throws Exception {
        Path source = temporaryDirectory.resolve("duplicate-source");
        Files.createDirectories(source);
        String row = "{\"action_id\":\"duplicate\",\"title\":\"Toy\",\"category\":\"toys\",\"price\":1.0,\"popularity\":0.5,\"novelty\":0.5}\n";
        Files.writeString(source.resolve("part-00000.jsonl"), row + row);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ProductCatalogStateGenerator().apply(
                        Map.of(ProductCatalogStateGenerator.SOURCE_NAME, List.of(source.toFile())),
                        new File(temporaryDirectory.toFile(), "duplicate-state")
                )
        );
        assertEquals("Duplicate catalog action_id: duplicate", error.getMessage());
    }
}
