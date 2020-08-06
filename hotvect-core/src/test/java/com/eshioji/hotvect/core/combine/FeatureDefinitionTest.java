package com.eshioji.hotvect.core.combine;

import com.eshioji.hotvect.core.TestHashedNamespace;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.Generate.enumValues;

class FeatureDefinitionTest {

    @Test
    void getComponents() {
        var subject = new FeatureDefinition<>(EnumSet.of(TestHashedNamespace.single_categorical_1));
        TestHashedNamespace[] expected = {TestHashedNamespace.single_categorical_1};
        assertArrayEquals(expected, subject.getComponents());
    }

    @Test
    void featureCannotBeEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureDefinition<>(EnumSet.noneOf(TestHashedNamespace.class)));
    }

    @Test
    void numericalMustBeSingleComponented() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureDefinition<>(EnumSet.of(TestHashedNamespace.single_numerical_1, TestHashedNamespace.single_string_1)));
    }

    @Test
    void equality() {
        var fd1 = new FeatureDefinition<>(EnumSet.of(TestHashedNamespace.single_categorical_1, TestHashedNamespace.strings_1));
        var fd2 = new FeatureDefinition<>(EnumSet.of(TestHashedNamespace.strings_1, TestHashedNamespace.single_categorical_1));
        var fd3 = new FeatureDefinition<>(EnumSet.of(TestHashedNamespace.strings_1));

        assertEquals(fd1, fd2);
        assertEquals(fd1.hashCode(), fd2.hashCode());
        assertNotEquals(fd1, fd3);
        assertNotEquals(fd3, fd1);
    }

    @Test
    void getName() {
        var subject = new FeatureDefinition<>(EnumSet.of(TestHashedNamespace.single_categorical_1, TestHashedNamespace.strings_1));
        assertEquals("single_categorical_1^strings_1", subject.getName());
    }

    @Test
    void getFeatureNamespace() {
        qt().forAll(enumValues(TestHashedNamespace.class)).checkAssert(x -> {
            var subject = new FeatureDefinition<>(EnumSet.of(x));
            assertEquals(subject.getFeatureNamespace(), Hashing.murmur3_32().hashUnencodedChars(subject.getName()).asInt());

        });
        var subject = new FeatureDefinition<>(EnumSet.of(TestHashedNamespace.single_categorical_1, TestHashedNamespace.strings_1));
        assertEquals(Hashing.murmur3_32().hashUnencodedChars("single_categorical_1^strings_1").asInt(), subject.getFeatureNamespace());
    }

}
