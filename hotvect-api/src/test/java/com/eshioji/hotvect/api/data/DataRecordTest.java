package com.eshioji.hotvect.api.data;

import org.junit.jupiter.api.Test;
import org.quicktheories.core.Gen;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.Generate.enumValues;
import static org.quicktheories.generators.SourceDSL.maps;

class DataRecordTest {
    @Test
    public void initializedMapBehavesConsistently() {
        qt().forAll(generate()).checkAssert(x -> {
            var enumMap = toEnumMap(x);
            var subject = toDataRecord(enumMap);

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
        });
    }


    @Test
    public void mutationBehavesConsistently() {
        qt().forAll(generate()).checkAssert(x -> {
            var subject = toDataRecord(x);

            var previous = subject.get(ExampleRawNamespace.strings_1);
            assertEquals(x.get(ExampleRawNamespace.strings_1), previous);

            subject.put(ExampleRawNamespace.strings_1, ExampleRawNamespace.strings_1);
            assertEquals(ExampleRawNamespace.strings_1, subject.get(ExampleRawNamespace.strings_1));

            subject.put(ExampleRawNamespace.strings_1, null);
            assertNull(subject.get(ExampleRawNamespace.strings_1));
        });
    }

    @Test
    public void nullKeyNotAllowed() {
        assertThrows(NullPointerException.class, () -> {
            var subject = new DataRecord<ExampleRawNamespace, String>(ExampleRawNamespace.class);
            subject.put(null, null);
        });
    }

    @Test
    public void equality() {
        qt().forAll(generate(), generate()).checkAssert((x, y) -> {
            var xd = toDataRecord(x);
            var yd = toDataRecord(y);
            if(x.equals(y)){
                assertEquals(xd.hashCode(), yd.hashCode());
                assertEquals(xd, yd);
            }
        });
    }


    private Gen<Map<ExampleRawNamespace, ExampleRawNamespace>> generate() {
        return maps().of(
                enumValues(ExampleRawNamespace.class),
                enumValues(ExampleRawNamespace.class))
                .ofSizeBetween(0, 2);
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