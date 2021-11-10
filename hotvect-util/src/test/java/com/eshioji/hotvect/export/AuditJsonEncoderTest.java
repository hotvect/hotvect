package com.eshioji.hotvect.export;

import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.audit.AuditableVectorizer;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.eshioji.hotvect.core.combine.InteractionCombiner;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.FeatureTransformer;
import com.eshioji.hotvect.core.transform.Transformer;
import com.eshioji.hotvect.core.vectorization.DefaultVectorizer;
import com.eshioji.hotvect.testutil.TestFeatureSpace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static com.eshioji.hotvect.testutil.TestFeatureSpace.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditJsonEncoderTest {
    private static final ObjectMapper OM = new ObjectMapper();

    private JsonNode expectedAuditRecord() throws Exception {
        return OM.readTree(AuditJsonEncoderTest.class.getResourceAsStream("audit_expected.json"));
    }

    private Map<String, String> inputFromExpected(JsonNode parsed) {
        ArrayNode features = (ArrayNode) parsed.get("features");
        return Arrays.stream(TestFeatureSpace.values()).collect(Collectors.toMap(
                Enum::toString,
                fs -> extract(fs, features)
        ));
    }

    ;

    private String extract(TestFeatureSpace fs, ArrayNode features) {
        var feature = Iterators.find(features.iterator(), f -> {
            try {
                return TestFeatureSpace.valueOf(f.get("feature_namespace").asText()) == fs;
            } catch (IllegalArgumentException e) {
                return false;
            }
        });
        switch (fs.getValueType()) {
            case CATEGORICAL:
                return feature.get("feature_name").asText();
            case NUMERICAL:
                return feature.get("value").asText();
            default:
                throw new AssertionError();
        }
    }

    @Test
    void apply() throws Exception {
        var subject = getSubject();

        var expected = OM.readTree(AuditJsonEncoderTest.class.getResourceAsStream("audit_expected.json"));
        var input = inputFromExpected(expected);

        JSONAssert.assertEquals(OM.writeValueAsString(expected),
                subject.apply(new Example<>(input, 1.0)),
                JSONCompareMode.LENIENT);
    }

    private AuditJsonEncoder<Map<String, String>> getSubject() {
        Transformer<Map<String, String>, TestFeatureSpace> testTransformer = new FeatureTransformer<>(TestFeatureSpace.class, ImmutableMap.of(
                feature1, s -> RawValue.singleString(s.get("feature1")),
                feature2, s -> RawValue.singleString(s.get("feature2")),
                feature3, s -> RawValue.singleNumerical(Double.parseDouble(s.get("feature3")))
        ));

        AuditableVectorizer<Map<String, String>> vectorizer = new DefaultVectorizer<>(testTransformer, new AuditableHasher<>(TestFeatureSpace.class), new InteractionCombiner<>(26, ImmutableSet.of(
                new FeatureDefinition<>(feature1),
                new FeatureDefinition<>(feature2),
                new FeatureDefinition<>(feature1, feature2),
                new FeatureDefinition<>(feature3)
        )));

        return new AuditJsonEncoder<>(vectorizer);
    }
}