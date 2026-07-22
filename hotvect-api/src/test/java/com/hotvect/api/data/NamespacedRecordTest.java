package com.hotvect.api.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NamespacedRecordTest {

    @ParameterizedTest
    @MethodSource("records")
    void initializedMapBehavesConsistently(Map<ExampleRawNamespace, ExampleRawNamespace> x) {
        EnumMap<ExampleRawNamespace, ExampleRawNamespace> enumMap = toEnumMap(x);
        DataRecord<ExampleRawNamespace, ExampleRawNamespace> subject = toDataRecord(enumMap);
        assertEquals(enumMap, subject.asEnumMap());
        for (ExampleRawNamespace k : ExampleRawNamespace.values()) {
            assertEquals(x.get(k), subject.get(k));
        }
        for (ExampleRawNamespace k : ExampleRawNamespace.values()) {
            if (x.get(k) != null) {
                subject.put(k, x.get(k));
                assertEquals(x.get(k), subject.get(k));
            }
            subject.put(k, null);
            assertNull(subject.get(k));
        }
    }

    @ParameterizedTest
    @MethodSource("records")
    void mutationBehavesConsistently(Map<ExampleRawNamespace, ExampleRawNamespace> x) {
        DataRecord<ExampleRawNamespace, ExampleRawNamespace> subject = toDataRecord(x);
        ExampleRawNamespace previous = subject.get(ExampleRawNamespace.strings_1);
        assertEquals(x.get(ExampleRawNamespace.strings_1), previous);
        subject.put(ExampleRawNamespace.strings_1, ExampleRawNamespace.strings_1);
        assertEquals(ExampleRawNamespace.strings_1, subject.get(ExampleRawNamespace.strings_1));
        subject.put(ExampleRawNamespace.strings_1, null);
        assertNull(subject.get(ExampleRawNamespace.strings_1));
    }

    @Test
    void nullKeyNotAllowed() {
        assertThrows(NullPointerException.class, () -> {
            DataRecord<ExampleRawNamespace, String> subject = new DataRecord<>(ExampleRawNamespace.class);
            subject.put(null, null);
        });
    }

    @ParameterizedTest
    @MethodSource("recordPairs")
    void equality(
            Map<ExampleRawNamespace, ExampleRawNamespace> x,
            Map<ExampleRawNamespace, ExampleRawNamespace> y
    ) {
        DataRecord<ExampleRawNamespace, ExampleRawNamespace> xd = toDataRecord(x);
        DataRecord<ExampleRawNamespace, ExampleRawNamespace> yd = toDataRecord(y);
        assertEquals(x.equals(y), xd.equals(yd));
        if (x.equals(y)) {
            assertEquals(xd.hashCode(), yd.hashCode());
        }
    }

    private static Stream<Map<ExampleRawNamespace, ExampleRawNamespace>> records() {
        return Stream.of(
                Map.of(),
                Map.of(ExampleRawNamespace.strings_1, ExampleRawNamespace.strings_1),
                Map.of(ExampleRawNamespace.strings_1, ExampleRawNamespace.single_numerical_1),
                Map.of(
                        ExampleRawNamespace.strings_1, ExampleRawNamespace.single_numerical_1,
                        ExampleRawNamespace.categoricals_1, ExampleRawNamespace.strings_1
                )
        );
    }

    private static Stream<Arguments> recordPairs() {
        Map<ExampleRawNamespace, ExampleRawNamespace> first = Map.of(
                ExampleRawNamespace.strings_1, ExampleRawNamespace.single_numerical_1
        );
        Map<ExampleRawNamespace, ExampleRawNamespace> same = Map.of(
                ExampleRawNamespace.strings_1, ExampleRawNamespace.single_numerical_1
        );
        Map<ExampleRawNamespace, ExampleRawNamespace> different = Map.of(
                ExampleRawNamespace.strings_1, ExampleRawNamespace.strings_1
        );
        return Stream.of(
                Arguments.of(Map.of(), Map.of()),
                Arguments.of(first, same),
                Arguments.of(first, different)
        );
    }

    private DataRecord<ExampleRawNamespace, ExampleRawNamespace> toDataRecord(EnumMap<ExampleRawNamespace, ExampleRawNamespace> enumMap) {
        return new DataRecord<>(ExampleRawNamespace.class, enumMap);
    }

    private DataRecord<ExampleRawNamespace, ExampleRawNamespace> toDataRecord(Map<ExampleRawNamespace, ExampleRawNamespace> map) {
        return new DataRecord<>(ExampleRawNamespace.class, toEnumMap(map));
    }

    private EnumMap<ExampleRawNamespace, ExampleRawNamespace> toEnumMap(Map<ExampleRawNamespace, ExampleRawNamespace> x) {
        return x.isEmpty() ? new EnumMap<>(ExampleRawNamespace.class) : new EnumMap<>(x);
    }
}
