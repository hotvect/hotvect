package com.hotvect.core.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.core.util.InvalidTransformationDefinitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("ConstantConditions")
public class NamespacesTest {

    /* ──────────────────────────── legacy enums that already implement Namespace ──────────────────────────── */

    enum TestA implements Namespace { testA1, testA2, testA3 }
    enum TestB implements Namespace { testB1, testB2, testB3 }
    enum TestC implements Namespace { testC1, testC2, testC3 }
    enum TestD implements Namespace { testD1, testD2, testD3 }

    /* ──────────────────────────── enum whose constants DO NOT implement Namespace ─────────────────────────── */

    enum PlainEnum { alpha, beta, gamma }

    /* ──────────────────────────── synthetic “flat” enum values for the performance test ───────────────────── */

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

    /* ──────────────────────────── fixture setup ──────────────────────────── */

    @BeforeEach
    void setUp() {
        Namespaces.clear();
    }

    /* ──────────────────────────── core round-trip verification ──────────────────────────── */

    @Test
    void compositeRoundTrip() {
        NamespacedRecord<Namespace, String> record = new NamespacedRecordImpl<>();
        for (TestA a : TestA.values()) {
            for (TestB b : TestB.values()) {
                for (TestC c : TestC.values()) {
                    Namespace ns = Namespaces.declareNamespace(a, b, c);
                    record.put(ns, Joiner.on("_").join(a, b, c));
                    Namespace probe = Namespaces.declareNamespace(a, b, c);
                    assertSame(ns, probe);
                    assertEquals(Joiner.on("_").join(a, b, c), record.get(probe));
                }
            }
        }
    }

    /* ──────────────────────────── new-spec tests – strings & plain enums ──────────────────────────── */

    @Test
    void testCompositeWithStringComponents() {
        Namespace foo = Namespaces.declareNamespace("foo");
        Namespace bar = Namespaces.declareNamespace("bar");

        Namespace composite = Namespaces.declareNamespace(foo, bar);
        assertEquals("foo_bar", composite.toString());

        Namespace viaString = Namespaces.declareNamespace("foo_bar");
        assertSame(composite, viaString);
    }

    @Test
    void testCompositeWithPlainEnumsConvertedToStringNamespaces() {
        Namespace alphaNs = Namespaces.declareNamespace(PlainEnum.alpha);
        Namespace betaNs  = Namespaces.declareNamespace(PlainEnum.beta);

        Namespace composite = Namespaces.declareNamespace(alphaNs, betaNs);
        assertEquals("alpha_beta", composite.toString());
    }

    /* ──────────────────────────── behavioural / validation tests ──────────────────────────── */

    @Test
    void singleNamespaceRoundTrip_legacyEnum() {
        Namespace first  = Namespaces.declareNamespace(TestA.testA1);
        Namespace second = Namespaces.declareNamespace(TestA.testA1);
        assertSame(first, second);
    }

    @Test
    void singleNamespaceRoundTrip_plainEnum() {
        Namespace first  = Namespaces.declareNamespace(PlainEnum.alpha);
        Namespace second = Namespaces.declareNamespace(PlainEnum.alpha);
        assertSame(first, second);
        assertEquals("alpha", first.toString());
    }

    @Test
    void testFeatureValueTypeSingleton() {
        Namespace n1 = Namespaces.declareFeatureNamespace(
                RawValueType.STRINGS, TestA.testA1, TestD.testD1);

        Namespace n2 = Namespaces.declareFeatureNamespace(
                RawValueType.STRINGS, TestA.testA1, TestD.testD1);

        assertSame(n1, n2);

        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareFeatureNamespace(
                        RawValueType.SINGLE_NUMERICAL, TestA.testA1, TestD.testD1));
    }

    @Test
    void featureNamespaceRequiresAtLeastTwoComponents() {
        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareFeatureNamespace(RawValueType.STRINGS, TestA.testA1));
    }

    @Test
    void nameCollisionCompositeVsString() {
        Namespace composite =
                Namespaces.declareNamespace(TestA.testA1, TestB.testB1);

        String duplicateName = composite.toString();
        Namespace viaString = Namespaces.declareNamespace(duplicateName);
        assertSame(composite, viaString);
    }

    @Test
    void nameCollisionStringVsComposite() {
        String name = "testA1_testB1";
        Namespace viaString = Namespaces.declareNamespace(name);

        Namespace viaComposite = Namespaces.declareNamespace(TestA.testA1, TestB.testB1);

        assertSame(viaString, viaComposite);
    }

    @Test
    void invalidStringNames() {
        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareNamespace(""));
        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareNamespace("with-hyphen"));
        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareNamespace("123numeric"));
        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareFeatureNamespace(RawValueType.STRINGS, ""));
        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareNamespace("_underscore"));
        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareFeatureNamespace(RawValueType.STRINGS, "_underscore"));
    }

    @Test
    void nullOrEmptyVarArgsValidation() {
        assertThrows(InvalidTransformationDefinitionException.class, () ->
                Namespaces.declareNamespace((Namespace[]) null));
        assertThrows(InvalidTransformationDefinitionException.class, () ->
                Namespaces.declareNamespace());
    }

    @Test
    void validStringName() {
        String name = "Alpha123_beta";
        Namespace ns = Namespaces.declareNamespace(name);
        assertEquals(name, ns.toString());
    }

    @Test
    void stringBasedNamespaceReturnTypeHintRules() {
        String name = "alpha";

        Namespace withHint = Namespaces.declareNamespace(String.class, name);
        assertEquals(String.class, withHint.getReturnTypeHint());

        Namespace withoutHint = Namespaces.declareNamespace(name);
        assertSame(withHint, withoutHint);

        assertThrows(IllegalArgumentException.class, () ->
                Namespaces.declareNamespace(Integer.class, name));
    }

    @Test
    void stringBasedFeatureNamespace() {
        String name = "vector_embedding";
        RawValueType vt = RawValueType.DENSE_VECTOR;
        Namespace fns = Namespaces.declareFeatureNamespace(vt, name);
        assertEquals(vt, fns.getFeatureValueType());
        assertEquals(vt.getJavaType(), fns.getReturnTypeHint());
    }

    /* ──────────────────────────── tryDeclareFeatureNamespace tests ──────────────────────────── */

    @Test
    void tryDeclareFeatureNamespace_returnsFeatureNsWhenTypePresent() {
        Namespace ns = Namespaces.tryDeclareFeatureNamespace(
                RawValueType.STRINGS,
                TestA.testA1, TestB.testB1);

        assertEquals(RawValueType.STRINGS, ns.getFeatureValueType());
        assertEquals("testA1_testB1",      ns.toString());

        /* second call with same parameters returns the identical singleton */
        Namespace again = Namespaces.tryDeclareFeatureNamespace(
                RawValueType.STRINGS,
                TestA.testA1, TestB.testB1);

        assertSame(ns, again);
    }

    @Test
    void tryDeclareFeatureNamespace_fallsBackToPlainWhenTypeIsNull() {
        Namespace ns = Namespaces.tryDeclareFeatureNamespace(
                null,
                TestA.testA2, TestB.testB2);

        assertNull(ns.getFeatureValueType());
        assertEquals("testA2_testB2", ns.toString());

        /* verify that the plain namespace clashes with an explicit plain declaration */
        Namespace viaPlain = Namespaces.declareNamespace(TestA.testA2, TestB.testB2);
        assertSame(ns, viaPlain);
    }

    @Test
    void tryDeclareFeatureNamespace_stringVariant() {
        Namespace ns = Namespaces.tryDeclareFeatureNamespace(
                RawValueType.SINGLE_NUMERICAL, "foo_bar");
        assertEquals(RawValueType.SINGLE_NUMERICAL, ns.getFeatureValueType());

        Namespace plain = Namespaces.tryDeclareFeatureNamespace(
                null, "baz_qux");
        assertNull(plain.getFeatureValueType());
        assertEquals("baz_qux", plain.toString());
    }

    /* ──────────────────────────── micro-benchmark (disabled by default) ──────────────────────────── */

    @Disabled("Takes too long to run in CI")
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

        int times = 100_000_000;
        double compoundIdentityResult    = nsPerOp(compound,   times, getCompoundKeys());
        double compoundConcatIdentityRes = nsPerOpConcat(compound, times, getCompoundKeysArr());
        double flatEnumMapResult         = nsPerOp(enumMap,    times, Iterators.cycle(TestABC.values()));
        double flatIdentityResult        = nsPerOp(flat,       times, Iterators.cycle(TestABC.values()));
        double flatHashMapResult         = nsPerOp(flatHashMap,times, Iterators.cycle(TestABC.values()));
        double stringHashMapResult       = nsPerOp(string,     times,
                Iterators.cycle(Arrays.stream(TestABC.values())
                        .map(Enum::toString).toList()));
        double stringConcatHashMapResult = nsPerOpConcatS(string, times, getConcatKeys());

        Map<String, Double> result = ImmutableMap.<String, Double>builder()
                .put("compoundIdentity",       compoundIdentityResult)
                .put("compoundConcatIdentity", compoundConcatIdentityRes)
                .put("flatEnumMap",            flatEnumMapResult)
                .put("flatIdentity",           flatIdentityResult)
                .put("flatHashMap",            flatHashMapResult)
                .put("stringHashMap",          stringHashMapResult)
                .put("stringConcatHashMap",    stringConcatHashMapResult)
                .build();

        TreeMap<String, Double> sorted = new TreeMap<>(Comparator.comparingDouble(result::get));
        sorted.putAll(result);
        System.out.println(new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(sorted));
    }

    /* ──────────────────────────── helpers used by the benchmark ──────────────────────────── */

    private Iterator<Namespace[]> getCompoundKeysArr() {
        List<Namespace[]> list = new ArrayList<>();
        for (TestA a : TestA.values()) for (TestB b : TestB.values()) for (TestC c : TestC.values())
            list.add(new Namespace[]{a, b, c});
        return Iterators.cycle(list);
    }

    private Iterator<List<String>> getConcatKeys() {
        Set<List<Enum<?>>> cartesian = Sets.cartesianProduct(
                EnumSet.allOf(TestA.class), EnumSet.allOf(TestB.class), EnumSet.allOf(TestC.class));
        return Iterators.cycle(cartesian.stream()
                .map(xs -> xs.stream().map(Enum::toString).collect(Collectors.toList()))
                .toList());
    }

    private void populate(EnumMap<TestABC, String> enumMap) {
        for (TestABC abc : TestABC.values()) enumMap.put(abc, abc.toString());
    }

    private Iterator<Namespace> getCompoundKeys() {
        List<Namespace> list = new ArrayList<>();
        for (TestA a : TestA.values()) for (TestB b : TestB.values()) for (TestC c : TestC.values())
            list.add(Namespaces.declareNamespace(a, b, c));
        return Iterators.cycle(list);
    }

    private <K> double nsPerOp(Map<K, String> map, int times, Iterator<K> keyCycle) {
        long blackhole = 0;
        long start = System.nanoTime();
        for (int i = 0; i < times; i++) {
            K k = keyCycle.next();
            for (int j = 0; j < 10; j++) {
                String v1 = map.get(k);
                blackhole += v1.hashCode() & 0x03;
                String v2 = map.put(k, v1);
                blackhole += v2.hashCode() & 0x03;
            }
        }
        long end = System.nanoTime();
        System.out.println("blackhole: " + blackhole);
        return (end - start) / ((double) times);
    }

    private static final Joiner JOINER = Joiner.on("_");

    private double nsPerOpConcatS(Map<String, String> map, int times, Iterator<List<String>> keyCycle) {
        long blackhole = 0;
        long start = System.nanoTime();
        for (int i = 0; i < times; i++) {
            String k = JOINER.join(keyCycle.next());
            for (int j = 0; j < 10; j++) {
                String v1 = map.get(k);
                blackhole += v1.hashCode() & 0x03;
                String v2 = map.put(k, v1);
                blackhole += v2.hashCode() & 0x03;
            }
        }
        long end = System.nanoTime();
        System.out.println("blackhole: " + blackhole);
        return (end - start) / ((double) times);
    }

    private double nsPerOpConcat(Map<Namespace, String> map, int times, Iterator<Namespace[]> keyCycle) {
        long blackhole = 0;
        long start = System.nanoTime();
        for (int i = 0; i < times; i++) {
            Namespace[] arr = keyCycle.next();
            Namespace k = Namespaces.declareNamespace(arr);
            for (int j = 0; j < 10; j++) {
                String v1 = map.get(k);
                blackhole += v1.hashCode() & 0x03;
                String v2 = map.put(k, v1);
                blackhole += v2.hashCode() & 0x03;
            }
        }
        long end = System.nanoTime();
        System.out.println("blackhole: " + blackhole);
        return (end - start) / ((double) times);
    }

    private void populateString(Map<String, String> map) {
        for (TestABC abc : TestABC.values()) map.put(abc.toString(), abc.toString());
    }

    private void populate(Map<Namespace, String> map) {
        for (TestABC abc : TestABC.values()) map.put(abc, abc.toString());
    }

    private void populate(IdentityHashMap<Namespace, String> map) {
        for (TestA a : TestA.values()) for (TestB b : TestB.values()) for (TestC c : TestC.values())
            map.put(Namespaces.declareNamespace(a, b, c),
                    Joiner.on("_").join(a, b, c));
    }

    /* ──────────────────────────── canonicalization tests ──────────────────────────── */

    @Test
    void registerEnumClass_registersAllConstants() {
        Namespaces.register(TestA.class);

        // Verify all enum constants are registered
        Namespace ns1 = Namespaces.declareNamespace("testA1");
        Namespace ns2 = Namespaces.declareNamespace("testA2");
        Namespace ns3 = Namespaces.declareNamespace("testA3");

        // String-based declarations should return the enum singletons
        assertSame(TestA.testA1, ns1);
        assertSame(TestA.testA2, ns2);
        assertSame(TestA.testA3, ns3);
    }

    @Test
    void registerEnumClass_detectsConflicts() {
        // Register string-based namespace first
        Namespace stringNs = Namespaces.declareNamespace("testA1");

        // Attempting to register enum with same name should fail
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                Namespaces.register(TestA.class));

        assertTrue(ex.getMessage().contains("testA1"));
        assertTrue(ex.getMessage().contains("already bound"));
    }

    @Test
    void assertCanonical_succeeds_whenNamespaceIsRegistered() {
        Namespaces.register(TestA.class);

        // Should not throw - the enum constant is canonical
        assertDoesNotThrow(() -> Namespaces.assertCanonical(TestA.testA1));
        assertDoesNotThrow(() -> Namespaces.assertCanonical(TestA.testA2));
    }

    @Test
    void assertCanonical_succeeds_afterStringDeclaration() {
        Namespace ns = Namespaces.declareNamespace("myNamespace");

        // Should not throw - the namespace was declared and is canonical
        assertDoesNotThrow(() -> Namespaces.assertCanonical(ns));
    }

    @Test
    void assertCanonical_autoRegistersEnum_whenNeverRegistered() {
        // TestA enum was never registered; assertCanonical should auto-register legacy enums for compatibility.
        assertDoesNotThrow(() -> Namespaces.assertCanonical(TestA.testA1));

        // After auto-registration, string-based declarations should resolve to the enum singletons.
        assertSame(TestA.testA1, Namespaces.declareNamespace("testA1"));
        assertSame(TestA.testA2, Namespaces.declareNamespace("testA2"));
    }

    @Test
    void assertCanonical_fails_whenDifferentInstance() {
        // Register via string first
        Namespace stringNs = Namespaces.declareNamespace("testA1");

        // The enum constant has the same name but different identity
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                Namespaces.assertCanonical(TestA.testA1));

        assertTrue(ex.getMessage().contains("not canonical"));
        assertTrue(ex.getMessage().contains("differs from registered singleton"));
        assertTrue(ex.getMessage().contains("testA1"));
    }

    @Test
    void assertCanonical_withEnumOverload_providesEnumTypeInfo() {
        // Force a non-canonical situation (string namespace created first).
        Namespaces.declareNamespace("testA1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                Namespaces.assertCanonical(TestA.testA1));

        assertTrue(ex.getMessage().contains("TestA"));
    }

    @Test
    void registerThenDeclare_enumBecomesCanonical() {
        // Register enum first
        Namespaces.register(TestB.class);

        // Later string declaration should return enum singleton
        Namespace ns = Namespaces.declareNamespace("testB1");
        assertSame(TestB.testB1, ns);

        // Identity lookups should succeed
        NamespacedRecord<Namespace, String> record = new NamespacedRecordImpl<>();
        record.put(TestB.testB1, "value");
        assertEquals("value", record.get(ns));
    }

    @Test
    void registerMultipleEnums_noConflictsWhenDistinctNames() {
        // Register multiple enum types with different names
        Namespaces.register(TestA.class);
        Namespaces.register(TestB.class);
        Namespaces.register(TestC.class);

        // All should be canonical
        assertDoesNotThrow(() -> Namespaces.assertCanonical(TestA.testA1));
        assertDoesNotThrow(() -> Namespaces.assertCanonical(TestB.testB2));
        assertDoesNotThrow(() -> Namespaces.assertCanonical(TestC.testC3));
    }

    @Test
    void register_idempotent_whenAlreadyRegistered() {
        // Register once
        Namespaces.register(TestD.class);

        // Register again - should be idempotent (no exception)
        assertDoesNotThrow(() -> Namespaces.register(TestD.class));

        // Verify still canonical
        assertDoesNotThrow(() -> Namespaces.assertCanonical(TestD.testD1));
    }

    @Test
    void assertCanonical_nullNamespace_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
                Namespaces.assertCanonical((Namespace) null));
    }

    @Test
    void register_nullEnumType_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
                Namespaces.register(null));
    }
}
