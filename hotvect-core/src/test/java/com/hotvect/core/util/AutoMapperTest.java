package com.hotvect.core.util;

import com.google.common.collect.Sets;
import com.hotvect.api.data.DataRecord;
import com.hotvect.core.TestFeatureNamespace;
import com.hotvect.core.TestRawNamespace;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoMapperTest {

    @Test
    void copiesMappedKeys() {
        Set<String> allRaw = EnumSet.allOf(TestRawNamespace.class).stream().map(Enum::name).collect(toSet());
        Set<String> allHashed = EnumSet.allOf(TestFeatureNamespace.class).stream().map(Enum::name).collect(toSet());

        Sets.SetView<String> expectedToBeMapped = Sets.intersection(allRaw, allHashed);
        assert expectedToBeMapped.size() < allHashed.size();

        AutoMapper<TestRawNamespace, TestFeatureNamespace, String> subject = new AutoMapper<>(TestRawNamespace.class, TestFeatureNamespace.class);

        assertEquals(expectedToBeMapped, subject.mapped().keySet().stream().map(Enum::name).collect(toSet()));


        EnumMap<TestRawNamespace, String> input = new EnumMap<>(TestRawNamespace.class);
        for (TestRawNamespace rawNs : TestRawNamespace.values()) {
            input.put(rawNs, rawNs.name());
        }
        DataRecord<TestRawNamespace, String> testRecord = new DataRecord<>(TestRawNamespace.class, input);
        DataRecord<TestFeatureNamespace, String> actual = subject.apply(testRecord);

        assertEquals(expectedToBeMapped, actual.asEnumMap().keySet().stream().map(Enum::name).collect(toSet()));

        actual.asEnumMap().forEach((key, value) -> assertEquals(key.name(), value));

        // Empty value
        input.remove(TestRawNamespace.single_categorical_1);
        testRecord = new DataRecord<>(TestRawNamespace.class, input);
        actual = subject.apply(testRecord);
        HashSet<String> expectedToBeMappedOneless =  expectedToBeMapped.copyInto(new HashSet<>());
        expectedToBeMappedOneless.remove(TestRawNamespace.single_categorical_1.name());

        assertEquals(expectedToBeMappedOneless, actual.asEnumMap().keySet().stream().map(Enum::name).collect(toSet()));

        actual.asEnumMap().forEach((key, value) -> assertEquals(key.name(), value));
    }

}