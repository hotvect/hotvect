package com.hotvect.tensorflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.*;
import com.hotvect.core.transform.Computable;
import com.hotvect.core.transform.Computing;
import com.hotvect.core.transform.TransformationMetadata;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import org.junit.jupiter.api.Test;
import org.tensorflow.proto.Example;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TFRecordRankingEncoderSimpleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testSchemaDescriptionReturnsValidJson() throws Exception {
        ComputingRankingTransformer<String, String> testTransformer = createMultiFeatureTransformer();
        TFRecordRankingEncoder<String, String, Double> encoder =
            new TFRecordRankingEncoder<>(testTransformer, outcome -> outcome);

        Optional<String> schemaOpt = encoder.schemaDescription();
        assertTrue(schemaOpt.isPresent());

        String schemaJson = schemaOpt.get();
        assertNotNull(schemaJson);

        // Should be valid JSON
        JsonNode root = MAPPER.readTree(schemaJson);
        assertNotNull(root);
        assertTrue(root.has("features"));

        JsonNode features = root.get("features");
        assertTrue(features.isArray());
        assertEquals(4, features.size());
    }

    @Test
    void testSchemaDescriptionContainsCorrectFeatures() throws Exception {
        ComputingRankingTransformer<String, String> testTransformer = createMultiFeatureTransformer();
        TFRecordRankingEncoder<String, String, Double> encoder =
            new TFRecordRankingEncoder<>(testTransformer, outcome -> outcome);

        String schemaJson = encoder.schemaDescription().get();
        JsonNode root = MAPPER.readTree(schemaJson);
        JsonNode features = root.get("features");

        Map<String, JsonNode> featureMap = new HashMap<>();
        for (JsonNode feature : features) {
            featureMap.put(feature.get("name").asText(), feature);
        }

        assertEquals("int64", featureMap.get("categorical_feature").get("dtype").asText());
        assertEquals("[]", featureMap.get("categorical_feature").get("shape").toString());

        assertEquals("float32", featureMap.get("numerical_feature").get("dtype").asText());
        assertEquals("[]", featureMap.get("numerical_feature").get("shape").toString());

        assertEquals("int64", featureMap.get("categorical_sequence").get("dtype").asText());
        assertEquals("[3]", featureMap.get("categorical_sequence").get("shape").toString());

        assertEquals("float32", featureMap.get("numerical_sequence").get("dtype").asText());
        assertEquals("[2]", featureMap.get("numerical_sequence").get("shape").toString());
    }

    @Test
    void testApplyWithEmptyActions() {
        ComputingRankingTransformer<String, String> testTransformer = createSimpleTransformer();
        TFRecordRankingEncoder<String, String, Double> encoder =
            new TFRecordRankingEncoder<>(testTransformer, outcome -> outcome);

        // Create empty ranking example
        OfflineRankingRequest<String, String> request = OfflineRankingRequest.newOfflineRankingRequest(
            "empty_example", "shared_data", Collections.emptyList());

        RankingExample<String, String, Double> example = new RankingExample<>(
            "empty_example", request, Collections.emptyList());

        // Execute
        ByteBuffer result = encoder.apply(example);

        // Should still return valid ByteBuffer (empty TFRecord)
        assertNotNull(result);
        assertEquals(0, result.remaining());
    }

    @Test
    void testApplyWithSingleAction() throws Exception {
        ComputingRankingTransformer<String, String> testTransformer = createSimpleTransformer();
        TFRecordRankingEncoder<String, String, Double> encoder =
            new TFRecordRankingEncoder<>(testTransformer, outcome -> outcome);

        // Create single action ranking example
        List<String> actions = Arrays.asList("action_0");
        OfflineRankingRequest<String, String> request = OfflineRankingRequest.newOfflineRankingRequest(
            "single_action", "shared_data", actions);

        List<RankingOutcome<Double, String>> outcomes = Arrays.asList(
            new RankingOutcome<>(new RankingDecision<>(0, "action_0"), 0.0)
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
            "single_action", request, outcomes);

        // Execute
        ByteBuffer result = encoder.apply(example);

        // Verify single record
        TFRecordCodec codec = new TFRecordCodec();
        ByteArrayInputStream bais = new ByteArrayInputStream(result.array(), result.position(), result.remaining());
        ReadableByteChannel channel = Channels.newChannel(bais);

        byte[] record = codec.read(channel);
        assertNotNull(record);

        // Verify it's a valid TensorFlow Example
        Example example1 = Example.parseFrom(record);
        assertNotNull(example1);

        // Should be no more records
        assertNull(codec.read(channel));
    }

    @Test
    void testFeatureValidationInConstructor() {
        // Create transformer with invalid feature type (non-TensorFlow type)
        ComputingRankingTransformer<String, String> invalidTransformer = new ComputingRankingTransformer<String, String>() {
            @Override
            public SortedSet<Namespace> getUsedFeatures() {
                SortedSet<Namespace> features = new TreeSet<>(Comparator.comparing(Namespace::getName));
                // Add a namespace with invalid feature type
                features.add(new Namespace() {
                    @Override
                    public String getName() {
                        return "invalid_feature";
                    }

                    @Override
                    public ValueType getFeatureValueType() {
                        // Return a non-TensorFlowFeatureType
                        return new ValueType() {
                            @Override
                            public boolean hasNumericValues() {
                                return false;
                            }

                            @Override
                            public String toString() {
                                return "InvalidType";
                            }
                        };
                    }

                    public int compareTo(Namespace other) {
                        return this.getName().compareTo(other.getName());
                    }
                });
                return features;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(String exampleId, String shared, List<Computable<String>> actions) {
                return null;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(RankingRequest<String, String> rankingRequest) {
                return null;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(ComputingRankingRequest<String, String> computingRankingRequest) {
                return null;
            }

            @Override
            public List<TransformedAction<String>> transform(ComputingRankingRequest<String, String> rankingRequest) {
                return Collections.emptyList();
            }

            @Override
            public List<TransformationMetadata> getTransformationMetadata() {
                return Collections.emptyList();
            }
        };

        // Should throw exception
        assertThrows(IllegalArgumentException.class,
            () -> new TFRecordRankingEncoder<>(invalidTransformer, (Double outcome) -> outcome));
    }

    private ComputingRankingTransformer<String, String> createSimpleTransformer() {
        return new ComputingRankingTransformer<String, String>() {
            @Override
            public SortedSet<Namespace> getUsedFeatures() {
                SortedSet<Namespace> features = new TreeSet<>(Comparator.comparing(Namespace::getName));
                features.add(createTestNamespace("test_feature", TensorFlowFeatureType.NUMERICAL));
                return features;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(String exampleId, String shared, List<Computable<String>> actions) {
                return null; // Not used in our tests
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(RankingRequest<String, String> rankingRequest) {
                return new ComputingRankingRequest<>(
                    rankingRequest,
                    Computing.builder(rankingRequest).build(),
                    Collections.emptyList()
                );
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(ComputingRankingRequest<String, String> computingRankingRequest) {
                return computingRankingRequest;
            }

            @Override
            public List<TransformedAction<String>> transform(ComputingRankingRequest<String, String> rankingRequest) {
                List<TransformedAction<String>> results = new ArrayList<>();
                List<String> actions = rankingRequest.rankingRequest().availableActions();

                for (int i = 0; i < actions.size(); i++) {
                    NamespacedRecord<Namespace, Object> record = new NamespacedRecordImpl<>();
                    Namespace testFeature = createTestNamespace("test_feature", TensorFlowFeatureType.NUMERICAL);
                    record.put(testFeature, (float) i); // Mock feature value

                    results.add(TransformedAction.of(actions.get(i), record));
                }

                return results;
            }

            @Override
            public List<TransformationMetadata> getTransformationMetadata() {
                return Collections.emptyList();
            }
        };
    }

    private ComputingRankingTransformer<String, String> createMultiFeatureTransformer() {
        return new ComputingRankingTransformer<String, String>() {
            @Override
            public SortedSet<Namespace> getUsedFeatures() {
                SortedSet<Namespace> features = new TreeSet<>(Comparator.comparing(Namespace::getName));
                features.add(createTestNamespace("categorical_feature", TensorFlowFeatureType.CATEGORICAL));
                features.add(createTestNamespace("numerical_feature", TensorFlowFeatureType.NUMERICAL));
                features.add(createTestNamespace("categorical_sequence", TensorFlowFeatureType.categoricalSequence(3)));
                features.add(createTestNamespace("numerical_sequence", TensorFlowFeatureType.numericalSequence(2)));
                return features;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(String exampleId, String shared, List<Computable<String>> actions) {
                return null;
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(RankingRequest<String, String> rankingRequest) {
                return new ComputingRankingRequest<>(
                    rankingRequest,
                    Computing.builder(rankingRequest).build(),
                    Collections.emptyList()
                );
            }

            @Override
            public ComputingRankingRequest<String, String> prepare(ComputingRankingRequest<String, String> computingRankingRequest) {
                return computingRankingRequest;
            }

            @Override
            public List<TransformedAction<String>> transform(ComputingRankingRequest<String, String> rankingRequest) {
                List<TransformedAction<String>> results = new ArrayList<>();
                List<String> actions = rankingRequest.rankingRequest().availableActions();

                for (String action : actions) {
                    NamespacedRecord<Namespace, Object> record = new NamespacedRecordImpl<>();
                    record.put(createTestNamespace("categorical_feature", TensorFlowFeatureType.CATEGORICAL), 1);
                    record.put(createTestNamespace("numerical_feature", TensorFlowFeatureType.NUMERICAL), 1.0f);
                    record.put(createTestNamespace("categorical_sequence", TensorFlowFeatureType.categoricalSequence(3)), new int[]{1, 2, 3});
                    record.put(createTestNamespace("numerical_sequence", TensorFlowFeatureType.numericalSequence(2)), new float[]{1.0f, 2.0f});

                    results.add(TransformedAction.of(action, record));
                }

                return results;
            }

            @Override
            public List<TransformationMetadata> getTransformationMetadata() {
                return Collections.emptyList();
            }
        };
    }

    private Namespace createTestNamespace(String name, TensorFlowFeatureType featureType) {
        return new Namespace() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public TensorFlowFeatureType getFeatureValueType() {
                return featureType;
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
}
