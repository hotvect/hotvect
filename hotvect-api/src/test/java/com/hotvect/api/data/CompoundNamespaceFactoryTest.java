package com.hotvect.api.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CompoundNamespaceFactoryTest {
    enum TestA implements Namespace {
        testA1, testA2, testA3
    }

    enum TestB implements Namespace {
        testB1, testB2, testB3
    }

    enum TestC implements Namespace {
        testC1, testC2, testC3
    }

    enum TestD implements Namespace {
        testD1, testD2, testD3
    }

    enum TestABC implements Namespace {
        testA1_testB1_testC1,
        testA1_testB1_testC2,
        testA1_testB1_testC3,
        testA2_testB1_testC1,
        testA2_testB1_testC2,
        testA2_testB1_testC3,
        testA3_testB1_testC1,
        testA3_testB1_testC2,
        testA3_testB1_testC3,
        testA1_testB2_testC1,
        testA1_testB2_testC2,
        testA1_testB2_testC3,
        testA2_testB2_testC1,
        testA2_testB2_testC2,
        testA2_testB2_testC3,
        testA3_testB2_testC1,
        testA3_testB2_testC2,
        testA3_testB2_testC3,
        testA1_testB3_testC1,
        testA1_testB3_testC2,
        testA1_testB3_testC3,
        testA2_testB3_testC1,
        testA2_testB3_testC2,
        testA2_testB3_testC3,
        testA3_testB3_testC1,
        testA3_testB3_testC2,
        testA3_testB3_testC3,
    }

    @BeforeEach
    void setUp() {
        // Clear the static state before each test
        CompoundNamespace.clear();
    }


    @Test
    void test() throws Exception {
        NamespacedRecord<Namespace, String> record = new NamespacedRecordImpl<>();
        for (TestA testA : TestA.values()) {
            for (TestB testB : TestB.values()) {
                for (TestC testC : TestC.values()) {
                    CompoundNamespace.NamespaceId theNameSpace = (CompoundNamespace.NamespaceId) CompoundNamespace.declareNamespace(testA, testB, testC);
                    record.put(theNameSpace, Joiner.on("_").join(testA, testB, testC));
                    CompoundNamespace.NamespaceId probe = (CompoundNamespace.NamespaceId) CompoundNamespace.declareNamespace(testA, testB, testC);
                    assertArrayEquals(new Namespace[]{testA, testB, testC}, probe.getNamespaces());
                    assertEquals(Joiner.on("_").join(testA, testB, testC), record.get(probe));
                }
            }
        }
    }

    @Disabled
    @Test
    void performanceTest() throws Exception {
        IdentityHashMap<Namespace, String> compound = new IdentityHashMap<>();
        populate(compound);
        Map<Namespace, String> flat = new IdentityHashMap<>();
        populate(flat);
        EnumMap<TestABC, String> enumMap = new EnumMap<>(TestABC.class);
        populate(enumMap);
        Map<Namespace, String> flatHashMap = new HashMap<>();
        populate(flatHashMap);
        Map<String, String> string = new HashMap<>();
        populateString(string);
        int times = 10000000;
        double compoundIdentityResult = nsPerOp(compound, times, getCompoundKeys());
        double compoundConcatIdentityResult = nsPerOpConcat(compound, times, getCompoundKeysArr());
        double flatEnumMapResult = nsPerOp(enumMap, times, Iterators.cycle(TestABC.values()));
        double flatIdentityResult = nsPerOp(flat, times, Iterators.cycle(TestABC.values()));
        double flatHashMapResult = nsPerOp(flatHashMap, times, Iterators.cycle(TestABC.values()));
        double stringHashMapResult = nsPerOp(string, times, Iterators.cycle(Arrays.stream(TestABC.values()).map(Enum::toString).collect(Collectors.toList())));
        double stringConcatHashMapResult = nsPerOpConcatS(string, times, getConcatKeys());
        Map<String, Double> result = ImmutableMap.of(
                "compoundIdentityResult", compoundIdentityResult,
                "compoundConcatIdentityResult", compoundConcatIdentityResult,
                "flatEnumMapResult", flatEnumMapResult,
                "flatIdentityResult", flatIdentityResult,
                "flatHashMapResult", flatHashMapResult,
                "stringHashMapResult", stringHashMapResult,
                "stringConcatHashMapResult", stringConcatHashMapResult
        );
        // Create a TreeMap with a custom comparator to sort by values
        TreeMap<String, Double> sortedByValues = new TreeMap<>(Comparator.comparingDouble(result::get));
        sortedByValues.putAll(result);
        System.out.println(new ObjectMapper()
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .writeValueAsString(sortedByValues));
    }

    private Iterator<Namespace[]> getCompoundKeysArr() {
        List<Namespace[]> allColumnNames = new ArrayList<>();
        for (TestA testA : TestA.values()) {
            for (TestB testB : TestB.values()) {
                for (TestC testC : TestC.values()) {
                    allColumnNames.add(new Namespace[]{testA, testB, testC});
                }
            }
        }
        return Iterators.cycle(allColumnNames);
    }

    private Iterator<List<String>> getConcatKeys() {
        Set<List<Enum<?>>> cartesian = Sets.cartesianProduct(EnumSet.allOf(TestA.class), EnumSet.allOf(TestB.class), EnumSet.allOf(TestC.class));
        return Iterators.cycle(cartesian.stream()
                .map(x -> x.stream().map(Enum::toString).collect(Collectors.toList()))
                .collect(Collectors.toList()));
    }

    private void populate(EnumMap<TestABC, String> enumMap) {
        for (TestABC testABC : TestABC.values()) {
            enumMap.put(testABC, testABC.toString());
        }
    }

    private Iterator<Namespace> getCompoundKeys() {
        List<Namespace> allColumnNames = new ArrayList<>();
        for (TestA testA : TestA.values()) {
            for (TestB testB : TestB.values()) {
                for (TestC testC : TestC.values()) {
                    allColumnNames.add(CompoundNamespace.declareNamespace(testA, testB, testC));
                }
            }
        }
        return Iterators.cycle(allColumnNames);
    }

    private <K> double nsPerOp(Map<K, String> compound, int times, Iterator<K> keyCycle) {
        long blackhole = 0;
        long start = System.nanoTime();
        for (int i = 0; i < times; i++) {
            K k = keyCycle.next();
            for (int j = 0; j < 10; j++) {
                String v1 = compound.get(k);
                blackhole += v1.hashCode() & 0x03;
                String v2 = compound.put(k, v1);
                blackhole += v2.hashCode() & 0x03;
            }
        }
        long end = System.nanoTime();
        System.out.println("blackhole: " + blackhole);
        return (end - start) / ((double) times);
    }

    private static final Joiner JOINER = Joiner.on("_");

    private double nsPerOpConcatS(Map<String, String> compound, int times, Iterator<List<String>> keyCycle) {
        long blackhole = 0;
        long start = System.nanoTime();
        for (int i = 0; i < times; i++) {
            String k = JOINER.join(keyCycle.next());
            for (int j = 0; j < 10; j++) {
                String v1 = compound.get(k);
                blackhole += v1.hashCode() & 0x03;
                String v2 = compound.put(k, v1);
                blackhole += v2.hashCode() & 0x03;
            }
        }
        long end = System.nanoTime();
        System.out.println("blackhole: " + blackhole);
        return (end - start) / ((double) times);
    }

    private double nsPerOpConcat(Map<Namespace, String> compound, int times, Iterator<Namespace[]> keyCycle) {
        long blackhole = 0;
        long start = System.nanoTime();
        for (int i = 0; i < times; i++) {
            Namespace[] namespaces = keyCycle.next();
            Namespace k = CompoundNamespace.declareNamespace(namespaces);
            for (int j = 0; j < 10; j++) {
                String v1 = compound.get(k);
                blackhole += v1.hashCode() & 0x03;
                String v2 = compound.put(k, v1);
                blackhole += v2.hashCode() & 0x03;
            }
        }
        long end = System.nanoTime();
        System.out.println("blackhole: " + blackhole);
        return (end - start) / ((double) times);
    }

    private void populateString(Map<String, String> string) {
        for (TestABC testABC : TestABC.values()) {
            string.put(testABC.toString(), testABC.toString());
        }
    }

    private void populate(Map<Namespace, String> implementation) {
        for (TestABC testABC : TestABC.values()) {
            implementation.put(testABC, testABC.toString());
        }
    }

    private void populate(IdentityHashMap<Namespace, String> reference) {
        for (TestA testA : TestA.values()) {
            for (TestB testB : TestB.values()) {
                for (TestC testC : TestC.values()) {
                    CompoundNamespace.NamespaceId theNameSpace = (CompoundNamespace.NamespaceId) CompoundNamespace.declareNamespace(testA, testB, testC);
                    reference.put(theNameSpace, Joiner.on("_").join(testA, testB, testC));
                }
            }
        }
    }

    @Test
    void testFeatureValueType() throws Exception {
        FeatureNamespace stringsOne = CompoundNamespace.declareFeatureNamespace(RawValueType.STRINGS, TestA.testA1, TestD.testD1);
        assertEquals(RawValueType.STRINGS, stringsOne.getFeatureValueType());
        stringsOne = CompoundNamespace.declareFeatureNamespace(RawValueType.STRINGS, TestA.testA1, TestD.testD1);
        assertEquals(RawValueType.STRINGS, stringsOne.getFeatureValueType());
        assertThrows(IllegalArgumentException.class, () -> {
            CompoundNamespace.declareFeatureNamespace(RawValueType.SINGLE_NUMERICAL, TestA.testA1, TestD.testD1);
        });
        FeatureNamespace singleCategoricalTwo = CompoundNamespace.declareFeatureNamespace(RawValueType.SINGLE_CATEGORICAL, TestA.testA1, TestD.testD2);
        assertEquals(RawValueType.SINGLE_CATEGORICAL, singleCategoricalTwo.getFeatureValueType());
    }

    @Test
    void testCompositeNamespacesMixedWithEnums() {
        // Create a composite namespace from TestB and TestC
        Namespace compositeBC = CompoundNamespace.declareNamespace(TestB.testB1, TestC.testC1);
        // Now create a composite namespace that includes an enum and the composite namespace
        Namespace compositeABC = CompoundNamespace.declareNamespace(TestA.testA1, compositeBC);
        assertTrue(compositeABC instanceof CompoundNamespace.NamespaceId);
        CompoundNamespace.NamespaceId namespaceId = (CompoundNamespace.NamespaceId) compositeABC;
        // The expected namespaces after flattening should be TestA.testA1, TestB.testB1, TestC.testC1
        Namespace[] expectedNamespaces = new Namespace[]{TestA.testA1, TestB.testB1, TestC.testC1};
        assertArrayEquals(expectedNamespaces, namespaceId.getNamespaces());
        assertEquals("testA1_testB1_testC1", namespaceId.toString());
    }

    @Test
    void testCompositeFeatureNamespaceMixedWithEnums() {
        // Create a composite namespace from TestA and TestB
        Namespace compositeAB = CompoundNamespace.declareNamespace(TestA.testA2, TestB.testB2);
        // Obtain a feature namespace by mixing an enum and the composite namespace
        FeatureNamespace featureNamespace = CompoundNamespace.declareFeatureNamespace(RawValueType.SINGLE_NUMERICAL, compositeAB, TestC.testC2);
        assertTrue(featureNamespace instanceof CompoundNamespace.FeatureNamespaceId);
        CompoundNamespace.FeatureNamespaceId featureNamespaceId = (CompoundNamespace.FeatureNamespaceId) featureNamespace;
        // The expected namespaces after flattening should be TestA.testA2, TestB.testB2, TestC.testC2
        Namespace[] expectedNamespaces = new Namespace[]{TestA.testA2, TestB.testB2, TestC.testC2};
        assertArrayEquals(expectedNamespaces, featureNamespaceId.getNamespaces());
        assertEquals("testA2_testB2_testC2", featureNamespaceId.toString());
        assertEquals(RawValueType.SINGLE_NUMERICAL, featureNamespaceId.getFeatureValueType());
    }

    @Test
    void testNestedCompositeNamespaces() {
        // Create composite namespaces
        Namespace compositeBC = CompoundNamespace.declareNamespace(TestB.testB3, TestC.testC3);
        Namespace compositeABC = CompoundNamespace.declareNamespace(TestA.testA3, compositeBC);
        // Use compositeABC as part of another composite namespace
        Namespace compositeABCD = CompoundNamespace.declareNamespace(compositeABC, TestD.testD1);
        assertTrue(compositeABCD instanceof CompoundNamespace.NamespaceId);
        CompoundNamespace.NamespaceId namespaceId = (CompoundNamespace.NamespaceId) compositeABCD;
        // The expected namespaces after flattening should be TestA.testA3, TestB.testB3, TestC.testC3, TestD.testD1
        Namespace[] expectedNamespaces = new Namespace[]{TestA.testA3, TestB.testB3, TestC.testC3, TestD.testD1};
        assertArrayEquals(expectedNamespaces, namespaceId.getNamespaces());
        assertEquals("testA3_testB3_testC3_testD1", namespaceId.toString());
    }

    @Test
    void testFeatureNamespaceWithNestedCompositeNamespaces() {
        // Create composite namespaces
        Namespace compositeAC = CompoundNamespace.declareNamespace(TestA.testA2, TestC.testC2);
        // Obtain a feature namespace using the composite namespace
        FeatureNamespace featureNamespace = CompoundNamespace.declareFeatureNamespace(RawValueType.STRINGS, TestB.testB1, compositeAC);
        assertTrue(featureNamespace instanceof CompoundNamespace.FeatureNamespaceId);
        CompoundNamespace.FeatureNamespaceId featureNamespaceId = (CompoundNamespace.FeatureNamespaceId) featureNamespace;
        // The expected namespaces after flattening should be TestB.testB1, TestA.testA2, TestC.testC2
        Namespace[] expectedNamespaces = new Namespace[]{TestB.testB1, TestA.testA2, TestC.testC2};
        assertArrayEquals(expectedNamespaces, featureNamespaceId.getNamespaces());
        assertEquals("testB1_testA2_testC2", featureNamespaceId.toString());
        assertEquals(RawValueType.STRINGS, featureNamespaceId.getFeatureValueType());
    }

    @Test
    void testExceptionForMismatchedFeatureValueTypesWithCompositeNamespaces() {
        // Use a unique combination of namespaces that hasn't been declared as a regular Namespace
        Namespace compositeAC = CompoundNamespace.declareNamespace(TestA.testA2, TestC.testC2);
        // Obtain a feature namespace with a specific ValueType using the composite namespace
        FeatureNamespace featureNamespace = CompoundNamespace.declareFeatureNamespace(RawValueType.SINGLE_CATEGORICAL, TestB.testB1, compositeAC, TestD.testD2);
        assertEquals(RawValueType.SINGLE_CATEGORICAL, featureNamespace.getFeatureValueType());
        // Attempt to obtain the same feature namespace with a different ValueType, expect exception
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            CompoundNamespace.declareFeatureNamespace(RawValueType.SINGLE_NUMERICAL, TestB.testB1, compositeAC, TestD.testD2);
        });
        String expectedMessage = "Attempted to retrieve a FeatureNamespace with ValueType SINGLE_NUMERICAL, but this namespace was already assigned ValueType SINGLE_CATEGORICAL";
        String actualMessage = exception.getMessage();
        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testFlatteningOrderPreserved() {
        // Create composite namespaces in different sequences
        Namespace compositeBA = CompoundNamespace.declareNamespace(TestB.testB2, TestA.testA2);
        Namespace compositeCAB = CompoundNamespace.declareNamespace(TestC.testC3, compositeBA);
        // Obtain a namespace using composite namespaces and enums
        Namespace compositeFull = CompoundNamespace.declareNamespace(compositeCAB, TestD.testD3);
        assertTrue(compositeFull instanceof CompoundNamespace.NamespaceId);
        CompoundNamespace.NamespaceId namespaceId = (CompoundNamespace.NamespaceId) compositeFull;
        // Expected namespaces after flattening: TestC.testC3, TestB.testB2, TestA.testA2, TestD.testD3
        Namespace[] expectedNamespaces = new Namespace[]{TestC.testC3, TestB.testB2, TestA.testA2, TestD.testD3};
        assertArrayEquals(expectedNamespaces, namespaceId.getNamespaces());
        assertEquals("testC3_testB2_testA2_testD3", namespaceId.toString());
    }

    @Test
    void testCreatingCompositeNamespaceWithSingleNamespaceThrowsException() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            CompoundNamespace.declareNamespace(TestA.testA1);
        });
        assertEquals("At least two namespaces are required to create a composite namespace", exception.getMessage());
    }

    // Added tests to verify the older deprecated methods still work
    @SuppressWarnings("removal")    @Test
    void testDeprecatedGetNamespace() {
        Namespace ns = CompoundNamespace.getNamespace(TestA.testA1, TestB.testB1, TestC.testC1);
        assertTrue(ns instanceof CompoundNamespace.NamespaceId);
        CompoundNamespace.NamespaceId nsId = (CompoundNamespace.NamespaceId) ns;
        Namespace[] expected = new Namespace[]{TestA.testA1, TestB.testB1, TestC.testC1};
        assertArrayEquals(expected, nsId.getNamespaces());
        assertEquals("testA1_testB1_testC1", nsId.toString());
    }

    @SuppressWarnings("removal")    @Test
    void testDeprecatedGetFeatureNamespace() {
        FeatureNamespace fns = CompoundNamespace.getFeatureNamespace(RawValueType.STRINGS, TestA.testA2, TestB.testB2, TestC.testC2);
        assertTrue(fns instanceof CompoundNamespace.FeatureNamespaceId);
        CompoundNamespace.FeatureNamespaceId fnsId = (CompoundNamespace.FeatureNamespaceId) fns;
        Namespace[] expected = new Namespace[]{TestA.testA2, TestB.testB2, TestC.testC2};
        assertArrayEquals(expected, fnsId.getNamespaces());
        assertEquals("testA2_testB2_testC2", fnsId.toString());
        assertEquals(RawValueType.STRINGS, fnsId.getFeatureValueType());
    }
}