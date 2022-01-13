package com.hotvect.core.combine;

import com.hotvect.core.TestFeatureNamespace;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.Generate.enumValues;

class FeatureDefinitionTest {

    @Test
    void getComponents() {
        FeatureDefinition<TestFeatureNamespace> subject = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.single_categorical_1));
        TestFeatureNamespace[] expected = {TestFeatureNamespace.single_categorical_1};
        assertArrayEquals(expected, subject.getComponents());
    }

    @Test
    void featureCannotBeEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureDefinition<>(EnumSet.noneOf(TestFeatureNamespace.class)));
    }

    @Test
    void numericalMustBeSingleComponented() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.single_numerical_1, TestFeatureNamespace.single_string_1)));
    }

    @Test
    void equality() {
        FeatureDefinition<TestFeatureNamespace> fd1 = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.single_categorical_1, TestFeatureNamespace.strings_1));
        FeatureDefinition<TestFeatureNamespace> fd2 = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.strings_1, TestFeatureNamespace.single_categorical_1));
        FeatureDefinition<TestFeatureNamespace> fd3 = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.strings_1));

        assertEquals(fd1, fd2);
        assertEquals(fd1.hashCode(), fd2.hashCode());
        assertNotEquals(fd1, fd3);
        assertNotEquals(fd3, fd1);
    }

    @Test
    void getName() {
        FeatureDefinition<TestFeatureNamespace> subject = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.single_categorical_1, TestFeatureNamespace.strings_1));
        assertEquals("single_categorical_1^strings_1", subject.getName());
    }

    @Test
    void getFeatureNamespace() {
        qt().forAll(enumValues(TestFeatureNamespace.class)).checkAssert(x -> {
            FeatureDefinition<TestFeatureNamespace> subject = new FeatureDefinition<>(EnumSet.of(x));
            assertEquals(subject.getFeatureNamespace(), Hashing.murmur3_32().hashUnencodedChars(subject.getName()).asInt());

        });
        FeatureDefinition<TestFeatureNamespace> subject = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.single_categorical_1, TestFeatureNamespace.strings_1));
        assertEquals(Hashing.murmur3_32().hashUnencodedChars("single_categorical_1^strings_1").asInt(), subject.getFeatureNamespace());
    }

}
