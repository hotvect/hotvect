package com.hotvect.api.data;

import net.jqwik.api.*;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NamespacedRecordTest {

    @Property
    void initializedMapBehavesConsistently(@ForAll("generate") Map<ExampleRawNamespace, ExampleRawNamespace> x) {
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

    @Property
    void mutationBehavesConsistently(@ForAll("generate") Map<ExampleRawNamespace, ExampleRawNamespace> x) {
        DataRecord<ExampleRawNamespace, ExampleRawNamespace> subject = toDataRecord(x);
        ExampleRawNamespace previous = subject.get(ExampleRawNamespace.strings_1);
        assertEquals(x.get(ExampleRawNamespace.strings_1), previous);
        subject.put(ExampleRawNamespace.strings_1, ExampleRawNamespace.strings_1);
        assertEquals(ExampleRawNamespace.strings_1, subject.get(ExampleRawNamespace.strings_1));
        subject.put(ExampleRawNamespace.strings_1, null);
        assertNull(subject.get(ExampleRawNamespace.strings_1));
    }

    @Example
    void nullKeyNotAllowed() {
        assertThrows(NullPointerException.class, () -> {
            DataRecord<ExampleRawNamespace, String> subject = new DataRecord<>(ExampleRawNamespace.class);
            subject.put(null, null);
        });
    }

    @Property
    void equality(@ForAll("generate") Map<ExampleRawNamespace, ExampleRawNamespace> x,
                  @ForAll("generate") Map<ExampleRawNamespace, ExampleRawNamespace> y) {
        DataRecord<ExampleRawNamespace, ExampleRawNamespace> xd = toDataRecord(x);
        DataRecord<ExampleRawNamespace, ExampleRawNamespace> yd = toDataRecord(y);
        if (x.equals(y)) {
            assertEquals(xd.hashCode(), yd.hashCode());
            assertEquals(xd, yd);
        }
    }

    @Provide
    Arbitrary<Map<ExampleRawNamespace, ExampleRawNamespace>> generate() {
        Arbitrary<ExampleRawNamespace> keys = Arbitraries.of(ExampleRawNamespace.values());
        Arbitrary<ExampleRawNamespace> values = Arbitraries.of(ExampleRawNamespace.values());
        return Arbitraries.maps(keys, values).ofMinSize(0).ofMaxSize(2);
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