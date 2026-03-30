package com.hotvect.catboost;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatBoostEncoderPropertyTest {

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
        CatBoostEncodingUtils.doAppendFeature(type, v, sb);
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

    @Example
    void nullTextUsesMissingTextSentinel() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.TEXT, null, sb);
        assertEquals(CatBoostEncodingUtils.MISSING_TEXT, sb.toString());
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
