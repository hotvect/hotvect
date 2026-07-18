package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleArtifactsTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path DATA_ROOT = Path.of("..", "example-data");

    @Test
    void algorithmDefinitionsExposeScorerRankerSearchIndexAndTopK() throws IOException {
        JsonNode scorer = definition("example-product-scorer");
        JsonNode ranker = definition("example-product-ranker");
        JsonNode searchIndex = definition("example-product-search-index");
        JsonNode topK = definition("example-product-search-topk");

        assertEquals("com.hotvect.catboost.CatBoostStreamingBulkScorerFactory", scorer.get("algorithm_factory_classname").textValue());
        assertEquals("com.hotvect.example.product.ProductTransformerFactory", scorer.get("transformer_factory_classname").textValue());
        assertEquals(List.of("example-product-scorer"), names(ranker.get("dependencies")));
        assertEquals(
                Set.of("example-product-search-index", "example-product-ranker"),
                Set.copyOf(names(topK.get("dependencies")))
        );
        assertEquals(
                "com.hotvect.example.product.ProductCatalogStateGeneratorFactory",
                searchIndex.get("generator_factory_classname").textValue()
        );
        assertEquals("example_product_catalog", searchIndex.at("/source_data/product_catalog/data_prefix").textValue());
        assertEquals("example_product_examples", scorer.at("/train_data_spec/data_prefix").textValue());
        String algorithmVersion = scorer.get("algorithm_version").textValue();
        assertEquals("1.2.3", algorithmVersion);
        assertFalse(algorithmVersion.contains("${"));
        assertFalse(scorer.get("hotvect_version").textValue().contains("${"));
        assertEquals(algorithmVersion, ranker.get("algorithm_version").textValue());
        assertEquals(algorithmVersion, searchIndex.get("algorithm_version").textValue());
        assertEquals(algorithmVersion, topK.get("algorithm_version").textValue());
        assertEquals("example_product_search_examples", topK.at("/test_data_spec/data_prefix").textValue());
        assertFalse(topK.has("demo_ui"));
    }

    @Test
    @SuppressWarnings("removal")
    void everyCommittedPartitionDecodesForItsPublicContract() throws IOException {
        Path dataRoot = DATA_ROOT.resolve("example_product_examples");
        List<Path> partitions;
        try (var paths = Files.walk(dataRoot)) {
            partitions = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        }
        assertEquals(3, partitions.size());

        int examples = 0;
        for (Path partition : partitions) {
            for (String line : Files.readAllLines(partition)) {
                var ranking = new ProductRankingDecoderFactory().apply(Optional.empty()).apply(line).getFirst();
                assertEquals(12, ranking.request().actions().size());
                assertEquals(12, ranking.outcomes().size());
                assertEquals(1, ranking.outcomes().stream().filter(outcome -> outcome.outcome().clicked()).count());
                examples++;
            }
        }
        assertEquals(60, examples);

        Path searchDataRoot = DATA_ROOT.resolve("example_product_search_examples");
        List<Path> searchPartitions;
        try (var paths = Files.walk(searchDataRoot)) {
            searchPartitions = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        }
        assertEquals(3, searchPartitions.size());

        int searchExamples = 0;
        for (Path partition : searchPartitions) {
            for (String line : Files.readAllLines(partition)) {
                var topK = new ProductSearchTopKDecoderFactory().apply(Optional.empty()).apply(line).getFirst();
                assertEquals(4, topK.request().k());
                assertEquals(12, topK.outcomes().size());
                assertEquals(1, topK.outcomes().stream().filter(outcome -> outcome.outcome().clicked()).count());
                searchExamples++;
            }
        }
        assertEquals(60, searchExamples);
    }

    @Test
    void imageManifestMatchesBundledFiles() throws Exception {
        JsonNode manifest = OBJECT_MAPPER.readTree(DATA_ROOT.resolve("action-images/manifest.json").toFile());
        assertEquals("Creative Commons Attribution 4.0 International", manifest.get("license").textValue());
        assertEquals(12, manifest.get("assets").size());

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (JsonNode asset : manifest.get("assets")) {
            Path image = DATA_ROOT.resolve("action-images").resolve(asset.get("file").textValue());
            assertEquals(asset.get("sha256").textValue(), HexFormat.of().formatHex(sha256.digest(Files.readAllBytes(image))));
            assertNotNull(asset.get("source_model_url").textValue());
        }
    }

    @Test
    void actionMetadataEmbedsEveryLicensedImage() throws Exception {
        Path metadataPath = DATA_ROOT.resolve("action-metadata/products.jsonl");
        List<String> rows = Files.readAllLines(metadataPath);
        assertEquals(12, rows.size());

        Set<String> actionIds = new HashSet<>();
        String dataUrlPrefix = "data:image/jpeg;base64,";
        for (String row : rows) {
            JsonNode metadata = OBJECT_MAPPER.readTree(row);
            String actionId = metadata.get("action_id").textValue();
            assertTrue(actionIds.add(actionId), actionId);
            assertEquals("Creative Commons Attribution 4.0 International", metadata.get("image_license").textValue());
            assertEquals("https://creativecommons.org/licenses/by/4.0/", metadata.get("image_license_url").textValue());

            String imageUrl = metadata.get("action_image_url").textValue();
            assertTrue(imageUrl.startsWith(dataUrlPrefix));
            byte[] embeddedImage = Base64.getDecoder().decode(imageUrl.substring(dataUrlPrefix.length()));
            byte[] committedImage = Files.readAllBytes(DATA_ROOT.resolve("action-images").resolve(actionId + ".jpg"));
            assertArrayEquals(committedImage, embeddedImage, actionId);
        }
    }

    private static JsonNode definition(String algorithmName) throws IOException {
        String resource = algorithmName + "-algorithm-definition.json";
        try (InputStream input = ExampleArtifactsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static List<String> names(JsonNode object) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
