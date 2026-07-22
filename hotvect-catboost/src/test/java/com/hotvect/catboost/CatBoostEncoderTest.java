package com.hotvect.catboost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatBoostEncoderTest {

    private void testDoAppendFeature(CatBoostFeatureType type, Object v) {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(type, v, sb);
    }

    @ParameterizedTest
    @MethodSource("categoricalValues")
    void categoricalAllowed(Object v) {
        testDoAppendFeature(CatBoostFeatureType.CATEGORICAL, v);
    }

    @ParameterizedTest
    @MethodSource("numericalValues")
    void numericalAllowed(Object v) {
        testDoAppendFeature(CatBoostFeatureType.NUMERICAL, v);
    }

    @ParameterizedTest
    @MethodSource("textValues")
    void textAllowed(Object v) {
        testDoAppendFeature(CatBoostFeatureType.TEXT, v);
    }

    @Test
    void nullTextUsesMissingTextSentinel() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.TEXT, null, sb);
        assertEquals(CatBoostEncodingUtils.MISSING_TEXT, sb.toString());
    }

    @ParameterizedTest
    @MethodSource("groupIdValues")
    void groupIdAllowed(Object v) {
        testDoAppendFeature(CatBoostFeatureType.GROUP_ID, v);
    }

    @ParameterizedTest
    @MethodSource("embeddingValues")
    void embeddingAllowed(Object v) {
        testDoAppendFeature(CatBoostFeatureType.EMBEDDING, v);
    }

    @ParameterizedTest
    @MethodSource("delimitedFieldValues")
    void categoricalDelimitedFieldEscapingRoundTrips(String raw) {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.CATEGORICAL, raw, sb);
        String encoded = sb.toString();

        assertEquals(raw, decodeEscapedDelimitedField(encoded));
        if (requiresDelimitedFieldEscaping(raw)) {
            assertTrue(encoded.length() >= 2 && encoded.startsWith("\"") && encoded.endsWith("\""));
        } else {
            assertEquals(raw, encoded);
        }
    }

    @Test
    void categoricalQuotesAreEscapedRfc4180Style() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.CATEGORICAL, "foo\"bar", sb);
        assertEquals("\"foo\"\"bar\"", sb.toString());
    }

    @Test
    void textQuotesAreEscapedRfc4180Style() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.TEXT, new String[]{"foo\"bar", "baz"}, sb);
        assertEquals("\"foo\"\"bar baz\"", sb.toString());
    }

    @Test
    void groupIdQuotesAreEscapedRfc4180Style() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.GROUP_ID, "grp\"1", sb);
        assertEquals("\"grp\"\"1\"", sb.toString());
    }

    @Test
    void categoricalTabsAreEscapedRfc4180Style() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.CATEGORICAL, "foo\tbar", sb);
        assertEquals("\"foo\tbar\"", sb.toString());
    }

    @Test
    void categoricalNewlinesAreEscapedRfc4180Style() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.CATEGORICAL, "foo\nbar", sb);
        assertEquals("\"foo\nbar\"", sb.toString());
    }

    @Test
    void groupIdCarriageReturnsAreEscapedRfc4180Style() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.GROUP_ID, "grp\r1", sb);
        assertEquals("\"grp\r1\"", sb.toString());
    }

    @Test
    void textTabsAreEscapedRfc4180Style() {
        StringBuilder sb = new StringBuilder();
        CatBoostEncodingUtils.doAppendFeature(CatBoostFeatureType.TEXT, new String[]{"foo\tbar", "baz"}, sb);
        assertEquals("\"foo\tbar baz\"", sb.toString());
    }

    private static Stream<Object> categoricalValues() {
        return Stream.of(null, "", "sku-1", "with space", 0, -1, 42L, true, false);
    }

    private static Stream<Object> numericalValues() {
        return Stream.of(null, -1.0d, 0.0d, 1.5d, Float.MIN_VALUE, Float.MAX_VALUE);
    }

    private static Stream<Arguments> textValues() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of((Object) new String[]{"a"}),
                Arguments.of((Object) new String[]{"alpha", "beta"})
        );
    }

    private static Stream<Object> groupIdValues() {
        return Stream.of(null, "", "group-1", "group with space");
    }

    private static Stream<Arguments> embeddingValues() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of((Object) new double[]{}),
                Arguments.of((Object) new double[]{-1.0, 0.0, 2.5}),
                Arguments.of((Object) new float[]{}),
                Arguments.of((Object) new float[]{-1.0f, 0.0f, 2.5f})
        );
    }

    private static Stream<String> delimitedFieldValues() {
        return Stream.of(
                "",
                "plain",
                "with space",
                "foo\tbar",
                "foo\nbar",
                "foo\rbar",
                "foo\"bar",
                "foo\t\"bar\"\n"
        );
    }

    private static boolean requiresDelimitedFieldEscaping(String raw) {
        return raw.indexOf('\t') >= 0
                || raw.indexOf('\n') >= 0
                || raw.indexOf('\r') >= 0
                || raw.indexOf('"') >= 0;
    }

    private static String decodeEscapedDelimitedField(String encoded) {
        if (encoded.length() >= 2 && encoded.startsWith("\"") && encoded.endsWith("\"")) {
            return encoded.substring(1, encoded.length() - 1).replace("\"\"", "\"");
        }
        return encoded;
    }
}
