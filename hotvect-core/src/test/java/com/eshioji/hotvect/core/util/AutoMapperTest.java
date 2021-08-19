package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.core.TestFeatureNamespace;
import com.eshioji.hotvect.core.TestRawNamespace;
import com.google.common.collect.Sets;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoMapperTest {

    @Test
    void copiesMappedKeys() {
        var allRaw = EnumSet.allOf(TestRawNamespace.class).stream().map(Enum::name).collect(toSet());
        var allHashed = EnumSet.allOf(TestFeatureNamespace.class).stream().map(Enum::name).collect(toSet());

        var expectedToBeMapped = Sets.intersection(allRaw, allHashed);
        assert expectedToBeMapped.size() < allHashed.size();

        AutoMapper<TestRawNamespace, TestFeatureNamespace, String> subject = new AutoMapper<>(TestRawNamespace.class, TestFeatureNamespace.class);

        assertEquals(expectedToBeMapped, subject.mapped().keySet().stream().map(Enum::name).collect(toSet()));


        var input = new EnumMap<TestRawNamespace, String>(TestRawNamespace.class);
        for (TestRawNamespace rawNs : TestRawNamespace.values()) {
            input.put(rawNs, rawNs.name());
        }
        var testRecord = new DataRecord<>(TestRawNamespace.class, input);
        var actual = subject.apply(testRecord);

        assertEquals(expectedToBeMapped, actual.asEnumMap().keySet().stream().map(Enum::name).collect(toSet()));

        actual.asEnumMap().forEach((key, value) -> assertEquals(key.name(), value));

        // Empty value
        input.remove(TestRawNamespace.single_categorical_1);
        testRecord = new DataRecord<>(TestRawNamespace.class, input);
        actual = subject.apply(testRecord);
        var expectedToBeMappedOneless =  expectedToBeMapped.copyInto(new HashSet<>());
        expectedToBeMappedOneless.remove(TestRawNamespace.single_categorical_1.name());

        assertEquals(expectedToBeMappedOneless, actual.asEnumMap().keySet().stream().map(Enum::name).collect(toSet()));

        actual.asEnumMap().forEach((key, value) -> assertEquals(key.name(), value));
    }

}