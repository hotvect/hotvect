package com.hotvect.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JsonFeatureSchemaGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonFeatureSchemaGenerator generator = new JsonFeatureSchemaGenerator();

    @Test
    void testGenerateSchemaWithAllFeatureTypes() throws Exception {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("request_cos_hour", "CATEGORICAL"),
            createNamespace("candidate_price", "NUMERICAL"),
            createNamespace("history_config_sku_list", "CATEGORICAL_SEQUENCE"),
            createNamespace("history_time_deltas", "NUMERICAL_SEQUENCE")
        );

        String schemaJson = generator.apply(transformer);
        assertNotNull(schemaJson);

        JsonNode root = MAPPER.readTree(schemaJson);
        assertTrue(root.has("features"));

        JsonNode features = root.get("features");
        assertTrue(features.isArray());
        assertEquals(4, features.size());

        // Verify each feature has name and type
        Map<String, String> featureMap = new HashMap<>();
        for (JsonNode feature : features) {
            assertTrue(feature.has("name"));
            assertTrue(feature.has("type"));
            featureMap.put(feature.get("name").asText(), feature.get("type").asText());
        }

        // Verify types are transparently passed through
        assertEquals("CATEGORICAL", featureMap.get("request_cos_hour"));
        assertEquals("NUMERICAL", featureMap.get("candidate_price"));
        assertEquals("CATEGORICAL_SEQUENCE", featureMap.get("history_config_sku_list"));
        assertEquals("NUMERICAL_SEQUENCE", featureMap.get("history_time_deltas"));
    }

    @Test
    void testGenerateSchemaWithOnlyNumericalFeatures() throws Exception {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("feature1", "NUMERICAL"),
            createNamespace("feature2", "NUMERICAL")
        );

        String schemaJson = generator.apply(transformer);
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        assertEquals(2, features.size());
        for (JsonNode feature : features) {
            assertEquals("NUMERICAL", feature.get("type").asText());
        }
    }

    @Test
    void testGenerateSchemaWithOnlyCategoricalFeatures() throws Exception {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("feature1", "CATEGORICAL"),
            createNamespace("feature2", "CATEGORICAL")
        );

        String schemaJson = generator.apply(transformer);
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        assertEquals(2, features.size());
        for (JsonNode feature : features) {
            assertEquals("CATEGORICAL", feature.get("type").asText());
        }
    }

    @Test
    void testGenerateSchemaWithOnlySequenceFeatures() throws Exception {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("categorical_seq", "CATEGORICAL_SEQUENCE"),
            createNamespace("numerical_seq", "NUMERICAL_SEQUENCE")
        );

        String schemaJson = generator.apply(transformer);
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        assertEquals(2, features.size());

        Map<String, String> featureMap = new HashMap<>();
        for (JsonNode feature : features) {
            featureMap.put(feature.get("name").asText(), feature.get("type").asText());
        }

        // Types are passed through as-is
        assertEquals("CATEGORICAL_SEQUENCE", featureMap.get("categorical_seq"));
        assertEquals("NUMERICAL_SEQUENCE", featureMap.get("numerical_seq"));
    }

    @Test
    void testGenerateSchemaPreservesFeatureOrdering() throws Exception {
        // SortedSet should maintain alphabetical order
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("z_feature", "NUMERICAL"),
            createNamespace("a_feature", "CATEGORICAL"),
            createNamespace("m_feature", "NUMERICAL_SEQUENCE")
        );

        String schemaJson = generator.apply(transformer);
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        assertEquals(3, features.size());

        // Verify alphabetical ordering
        assertEquals("a_feature", features.get(0).get("name").asText());
        assertEquals("m_feature", features.get(1).get("name").asText());
        assertEquals("z_feature", features.get(2).get("name").asText());
    }

    @Test
    void testGenerateSchemaWithStringFeatureType() throws Exception {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("string_feature", "STRING"),
            createNamespace("numerical_feature", "NUMERICAL")
        );

        String schemaJson = generator.apply(transformer);
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        Map<String, String> featureMap = new HashMap<>();
        for (JsonNode feature : features) {
            featureMap.put(feature.get("name").asText(), feature.get("type").asText());
        }

        // STRING features are transparently passed through
        assertEquals("NUMERICAL", featureMap.get("numerical_feature"));
        assertEquals("STRING", featureMap.get("string_feature"));
    }

    @Test
    void testGenerateSchemaRejectsEmptyFeatures() {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> generator.apply(transformer)
        );

        assertTrue(exception.getMessage().contains("cannot be empty"));
    }

    @Test
    void testGenerateSchemaTransparentlyPassesAnyFeatureType() throws Exception {
        RankingTransformer<?, ?> transformer = new MockRankingTransformer() {
            @Override
            public SortedSet<Namespace> getUsedFeatures() {
                SortedSet<Namespace> features = new TreeSet<>(Comparator.comparing(Namespace::getName));
                features.add(new Namespace() {
                    @Override
                    public String getName() {
                        return "custom_feature";
                    }

                    @Override
                    public ValueType getFeatureValueType() {
                        return new ValueType() {
                            @Override
                            public boolean hasNumericValues() {
                                return false;
                            }

                            @Override
                            public String toString() {
                                return "CUSTOM_TYPE";
                            }
                        };
                    }

                    public int compareTo(Namespace other) {
                        return this.getName().compareTo(other.getName());
                    }
                });
                return features;
            }
        };

        String schemaJson = generator.apply(transformer);
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        assertEquals(1, features.size());
        assertEquals("custom_feature", features.get(0).get("name").asText());
        assertEquals("CUSTOM_TYPE", features.get(0).get("type").asText());
    }

    @Test
    void testGenerateSchemaProducesValidJson() throws Exception {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("test_feature", "NUMERICAL")
        );

        String schemaJson = generator.apply(transformer);

        // Should be valid JSON
        JsonNode root = MAPPER.readTree(schemaJson);
        assertNotNull(root);

        // Should be pretty printed (contains newlines)
        assertTrue(schemaJson.contains("\n"));
    }

    @Test
    void testGenerateSchemaWithComplexFeatureNames() throws Exception {
        RankingTransformer<?, ?> transformer = createTransformerWithFeatures(
            createNamespace("request_cos_hour", "CATEGORICAL"),
            createNamespace("contact_brand_name_match_count", "NUMERICAL"),
            createNamespace("example_add2cart_brand_name_time_since_last_match", "NUMERICAL")
        );

        String schemaJson = generator.apply(transformer);
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        assertEquals(3, features.size());

        // Verify complex names are preserved
        Set<String> names = new HashSet<>();
        for (JsonNode feature : features) {
            names.add(feature.get("name").asText());
        }

        assertTrue(names.contains("request_cos_hour"));
        assertTrue(names.contains("contact_brand_name_match_count"));
        assertTrue(names.contains("example_add2cart_brand_name_time_since_last_match"));
    }

    // Helper methods

    private RankingTransformer<?, ?> createTransformerWithFeatures(Namespace... namespaces) {
        return new MockRankingTransformer() {
            @Override
            public SortedSet<Namespace> getUsedFeatures() {
                SortedSet<Namespace> features = new TreeSet<>(Comparator.comparing(Namespace::getName));
                features.addAll(Arrays.asList(namespaces));
                return features;
            }
        };
    }

    private Namespace createNamespace(String name, String typeName) {
        return new Namespace() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public ValueType getFeatureValueType() {
                return new ValueType() {
                    @Override
                    public boolean hasNumericValues() {
                        return typeName.equals("NUMERICAL") || typeName.contains("NUMERICAL");
                    }

                    @Override
                    public String toString() {
                        return typeName;
                    }
                };
            }

            public int compareTo(Namespace other) {
                return this.getName().compareTo(other.getName());
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof Namespace)) return false;
                return getName().equals(((Namespace) obj).getName());
            }

            @Override
            public int hashCode() {
                return getName().hashCode();
            }
        };
    }

    private static abstract class MockRankingTransformer implements RankingTransformer<Object, Object> {
        // Intentionally empty - subclasses only need to implement getUsedFeatures()
    }
}
