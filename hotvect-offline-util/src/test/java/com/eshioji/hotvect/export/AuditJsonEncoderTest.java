package com.eshioji.hotvect.export;

import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.eshioji.hotvect.core.audit.AuditableScoringVectorizer;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.eshioji.hotvect.core.combine.InteractionCombiner;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.regression.ScoringFeatureTransformer;
import com.eshioji.hotvect.core.transform.regression.ScoringTransformer;
import com.eshioji.hotvect.core.vectorization.scoring.DefaultScoringVectorizer;
import com.eshioji.hotvect.offlineutils.export.JsonScoringExampleEncoder;
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
        JsonNode feature = Iterators.find(features.iterator(), f -> {
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
        JsonScoringExampleEncoder<Map<String, String>, Double> subject = getSubject();

        JsonNode expected = OM.readTree(AuditJsonEncoderTest.class.getResourceAsStream("audit_expected.json"));
        Map<String, String> input = inputFromExpected(expected);

        var expectedStr = OM.writeValueAsString(expected);
        var actualStr = subject.apply(new ScoringExample<>(input, 1.0));
        JSONAssert.assertEquals(expectedStr,
                actualStr,
                JSONCompareMode.LENIENT);
    }

    private JsonScoringExampleEncoder<Map<String, String>, Double> getSubject() {
        ScoringTransformer<Map<String, String>, TestFeatureSpace> testScoringTransformer = new ScoringFeatureTransformer<>(TestFeatureSpace.class, ImmutableMap.of(
                feature1, s -> RawValue.singleString(s.get("feature1")),
                feature2, s -> RawValue.singleString(s.get("feature2")),
                feature3, s -> RawValue.singleNumerical(Double.parseDouble(s.get("feature3")))
        ));

        AuditableScoringVectorizer<Map<String, String>> vectorizer = new DefaultScoringVectorizer<>(testScoringTransformer, new AuditableHasher<>(TestFeatureSpace.class), new InteractionCombiner<>(26, ImmutableSet.of(
                new FeatureDefinition<>(feature1),
                new FeatureDefinition<>(feature2),
                new FeatureDefinition<>(feature1, feature2),
                new FeatureDefinition<>(feature3)
        )));

        return new JsonScoringExampleEncoder<>(vectorizer, d -> d);
    }
}