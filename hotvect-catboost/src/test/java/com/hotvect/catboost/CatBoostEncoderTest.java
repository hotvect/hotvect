package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.Computable;
import com.hotvect.core.transform.Computing;
import com.hotvect.core.transform.TransformationMetadata;
import com.hotvect.core.transform.ranking.ComputingCandidate;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import net.jqwik.api.*;

import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

class CatBoostEncoderPropertyTest {

    enum TestNamespace implements Namespace {
        TEST_CATEGORICAL {
            @Override public ValueType getFeatureValueType() { return CatBoostFeatureType.CATEGORICAL; }
        },
        TEST_NUMERICAL {
            @Override public ValueType getFeatureValueType() { return CatBoostFeatureType.NUMERICAL; }
        },
        TEST_TEXT {
            @Override public ValueType getFeatureValueType() { return CatBoostFeatureType.TEXT; }
        },
        TEST_GROUP_ID {
            @Override public ValueType getFeatureValueType() { return CatBoostFeatureType.GROUP_ID; }
        },
        TEST_EMBEDDING {
            @Override public ValueType getFeatureValueType() { return CatBoostFeatureType.EMBEDDING; }
        }
    }

    // Dummy transformer that returns an empty set of features and no transformations
    private final ComputingRankingTransformer<Object, Object> dummyTransformer = new ComputingRankingTransformer<>() {
        @Override
        public SortedSet<Namespace> getUsedFeatures() {
            return new TreeSet<>();
        }

        @Override
        public ComputingRankingRequest<Object, Object> prepare(String exampleId, Object shared, List<Computable<Object>> actions) {
            // Return a request with no candidates
            RankingRequest<Object, Object> rankingRequest = new RankingRequest<>(exampleId, shared, Collections.emptyList());
            return new ComputingRankingRequest<>(
                    rankingRequest,
                    Computing.builder(rankingRequest).build(),
                    Collections.<ComputingCandidate<Object, Object>>emptyList()
            );
        }

        @Override
        public ComputingRankingRequest<Object, Object> prepare(RankingRequest<Object, Object> rankingRequest) {
            return new ComputingRankingRequest<>(
                    rankingRequest,
                    Computing.builder(rankingRequest).build(),
                    Collections.<ComputingCandidate<Object, Object>>emptyList()
            );
        }

        @Override
        public ComputingRankingRequest<Object, Object> prepare(ComputingRankingRequest<Object, Object> computingRankingRequest) {
            return computingRankingRequest;
        }

        @Override
        public List<TransformedAction<Object>> transform(ComputingRankingRequest<Object, Object> rankingRequest) {
            return Collections.emptyList();
        }

        @Override
        public List<TransformationMetadata> getTransformationMetadata() {
            // Return empty metadata list
            return Collections.emptyList();
        }
    };

    // Dummy reward function that returns 0.0 for any outcome
    private final RewardFunction<Object> dummyRewardFunction = outcome -> 0.0;

    @Provide
    Arbitrary<Object> categoricalValues() {
        Arbitrary<Object> nullVal = Arbitraries.just((Object) null);
        Arbitrary<Object> strVal = Arbitraries.strings().ofMinLength(1).ofMaxLength(10).map(s -> (Object)s);
        Arbitrary<Object> intVal = Arbitraries.integers().map(i -> (Object)i);
        Arbitrary<Object> longVal = Arbitraries.longs().map(l -> (Object)l);
        return Arbitraries.oneOf(nullVal, strVal, intVal, longVal);
    }

    @Provide
    Arbitrary<Object> numericalValues() {
        Arbitrary<Object> nullVal = Arbitraries.just((Object) null);
        Arbitrary<Object> doubleVal = Arbitraries.doubles().map(d -> (Object)d);
        Arbitrary<Object> floatVal = Arbitraries.floats().map(f -> (Object)f);
        return Arbitraries.oneOf(nullVal, doubleVal, floatVal);
    }

    @Provide
    Arbitrary<Object> textValues() {
        Arbitrary<Object> nullVal = Arbitraries.just((Object)null);
        Arbitrary<String> textElement = Arbitraries.strings()
                .withCharRange('a','z')
                .ofMinLength(1).ofMaxLength(5)
                .filter(s -> !s.contains(" "));
        Arbitrary<String[]> nonEmptyArray = textElement.array(String[].class).ofMinSize(1);
        Arbitrary<Object> arrVal = nonEmptyArray.map(arr -> (Object)arr);
        return Arbitraries.oneOf(nullVal, arrVal);
    }

    @Provide
    Arbitrary<Object> groupIdValues() {
        Arbitrary<Object> nullVal = Arbitraries.just((Object)null);
        Arbitrary<Object> strVal = Arbitraries.strings().ofMinLength(1).ofMaxLength(10).map(s -> (Object)s);
        return Arbitraries.oneOf(nullVal, strVal);
    }

    @Provide
    Arbitrary<Object> embeddingValues() {
        Arbitrary<Object> nullVal = Arbitraries.just((Object)null);

        Arbitrary<double[]> doubleArr = Arbitraries.doubles().array(double[].class).ofMinSize(0).ofMaxSize(5);
        Arbitrary<Object> doubleObj = doubleArr.map(a -> (Object) a);

        Arbitrary<float[]> floatArr = Arbitraries.floats().array(float[].class).ofMinSize(0).ofMaxSize(5);
        Arbitrary<Object> floatObj = floatArr.map(a -> (Object) a);

        return Arbitraries.oneOf(nullVal, doubleObj, floatObj);
    }

    private void testDoAppendFeature(CatBoostFeatureType type, Object v) {
        StringBuilder sb = new StringBuilder();
        // Pass the dummy transformer and dummy reward function
        CatBoostEncoder<Object,Object,Object> encoder = new CatBoostEncoder<>(dummyTransformer, dummyRewardFunction);
        encoderTestDoAppendFeature(encoder, type, v, sb);
    }

    private void encoderTestDoAppendFeature(CatBoostEncoder<Object,Object,Object> encoder, CatBoostFeatureType valueType, Object v, StringBuilder sb) {
        try {
            var method = CatBoostEncoder.class
                    .getDeclaredMethod("doAppendFeature", CatBoostFeatureType.class, Object.class, StringBuilder.class);
            method.setAccessible(true);
            method.invoke(encoder, valueType, v, sb);
        } catch (Exception e) {
            throw new RuntimeException("Failed for valueType="+valueType+" value="+v+" exception="+e.getCause(), e);
        }
    }

    @Property
    void categoricalAllowed(@ForAll("categoricalValues") Object v) {
        testDoAppendFeature(CatBoostFeatureType.CATEGORICAL, v);
    }

    @Property
    void numericalAllowed(@ForAll("numericalValues") Object v) {
        testDoAppendFeature(CatBoostFeatureType.NUMERICAL, v);
    }

    @Property
    void textAllowed(@ForAll("textValues") Object v) {
        testDoAppendFeature(CatBoostFeatureType.TEXT, v);
    }

    @Property
    void groupIdAllowed(@ForAll("groupIdValues") Object v) {
        testDoAppendFeature(CatBoostFeatureType.GROUP_ID, v);
    }

    @Property
    void embeddingAllowed(@ForAll("embeddingValues") Object v) {
        testDoAppendFeature(CatBoostFeatureType.EMBEDDING, v);
    }

}
