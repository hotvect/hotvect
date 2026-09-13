package com.hotvect.api.algodefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlgorithmDependenciesTest {

    @Test
    void exposesValidatedSingletonSelections() {
        Algorithm first = new Algorithm() {};
        TypeToken<Algorithm> algorithmType = new TypeToken<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "single", instance("first", first, new TypeToken<Algorithm>() {})));

        assertSame(first, dependencies.only("single", algorithmType));
    }

    @Test
    void exposesUncheckedSingletonSelections() {
        Algorithm first = new Algorithm() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "single", instance("first", first, new TypeToken<Algorithm>() {})));

        Algorithm single = dependencies.only("single");

        assertSame(first, single);
    }

    @Test
    void exposesGenericFactoryContractsThroughUncheckedSelections() {
        GenericAlgorithm<String> algorithm = new GenericAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "generic", genericInstance("generic", algorithm)));

        GenericAlgorithm<String> selected = dependencies.only("generic");

        assertSame(algorithm, selected);
    }

    @Test
    void acceptsUnresolvedGenericFactoryContractsThroughTypedSelections() {
        GenericAlgorithm<String> algorithm = new GenericAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "generic", genericInstance("generic", algorithm)));

        assertSame(algorithm, dependencies.only("generic", new TypeToken<GenericAlgorithm<String>>() {}));
    }

    @Test
    void rejectsWrongAlgorithmInterfacesEvenWhenGenericArgumentsAreUnresolved() {
        GenericAlgorithm<String> algorithm = new GenericAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "generic", genericInstance("generic", algorithm)));

        assertThrows(IllegalArgumentException.class, () -> dependencies.only(
                "generic", new TypeToken<com.hotvect.api.algorithms.Scorer<String>>() {}));
    }

    @Test
    void validatesConcreteSuperinterfacesDespiteUnrelatedTypeVariables() {
        GenericStringAlgorithm<Object> algorithm = new GenericStringAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "strings", genericStringInstance(algorithm)));

        assertSame(algorithm, dependencies.only("strings", new TypeToken<GenericAlgorithm<String>>() {}));
        assertThrows(IllegalArgumentException.class, () -> dependencies.only(
                "strings", new TypeToken<GenericAlgorithm<Integer>>() {}));
    }

    @Test
    void validatesParameterizedAlgorithmTypesWithoutErasure() {
        GenericAlgorithm<String> first = new GenericAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "single", instance("first", first, new TypeToken<GenericAlgorithm<String>>() {})));

        GenericAlgorithm<String> single = dependencies.only(
                "single",
                new TypeToken<GenericAlgorithm<String>>() {});
        GenericAlgorithm<String> uncheckedSingle = dependencies.only("single");

        assertSame(first, single);
        assertSame(first, uncheckedSingle);
    }

    @Test
    void rejectsIncompatibleNestedGenericContracts() {
        GenericAlgorithm<List<String>> algorithm = new GenericAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "single",
                instance(
                        "strings",
                        algorithm,
                        new TypeToken<GenericAlgorithm<List<String>>>() {})));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> dependencies.only("single", new TypeToken<GenericAlgorithm<List<Integer>>>() {}));

        assertTrue(failure.getMessage().contains("GenericAlgorithm<java.util.List<java.lang.String>>"));
        assertTrue(failure.getMessage().contains("GenericAlgorithm<java.util.List<java.lang.Integer>>"));
    }

    @Test
    void rejectsIncompatibleGenericContracts() {
        GenericAlgorithm<String> algorithm = new GenericAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "single", instance("strings", algorithm, new TypeToken<GenericAlgorithm<String>>() {})));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> dependencies.only("single", new TypeToken<GenericAlgorithm<Integer>>() {}));

        assertTrue(failure.getMessage().contains("GenericAlgorithm<java.lang.String>"));
        assertTrue(failure.getMessage().contains("GenericAlgorithm<java.lang.Integer>"));
    }

    @Test
    void resolvesGenericSuperinterfacesOfConcreteImplementationContracts() {
        ConcreteStringAlgorithm algorithm = new ConcreteStringAlgorithm();
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "single", instance("strings", algorithm, TypeToken.of(ConcreteStringAlgorithm.class))));

        assertSame(
                algorithm,
                dependencies.only("single", new TypeToken<GenericAlgorithm<String>>() {}));
    }

    @Test
    void rejectsRawAndWildcardTypeTokenLookup() {
        GenericAlgorithm<String> algorithm = new GenericAlgorithm<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "single", instance("strings", algorithm, new TypeToken<GenericAlgorithm<String>>() {})));

        @SuppressWarnings({"rawtypes", "unchecked"})
        TypeToken<GenericAlgorithm> rawType = TypeToken.of(GenericAlgorithm.class);
        IllegalArgumentException rawFailure = assertThrows(
                IllegalArgumentException.class,
                () -> dependencies.only("single", rawType));
        IllegalArgumentException wildcardFailure = assertThrows(
                IllegalArgumentException.class,
                () -> dependencies.only("single", new TypeToken<GenericAlgorithm<?>>() {}));

        assertEquals(
                "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "
                        + GenericAlgorithm.class.getName(),
                rawFailure.getMessage());
        assertEquals(
                "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "
                        + GenericAlgorithm.class.getName() + "<?>",
                wildcardFailure.getMessage());
    }

    @Test
    void requiresAConcreteOwnerForNonStaticMemberAlgorithms() {
        GenericOuter<String> outer = new GenericOuter<>();
        GenericOuter<String>.InnerAlgorithm algorithm = outer.new InnerAlgorithm();
        TypeToken<GenericOuter<String>.InnerAlgorithm> concreteType = new TypeToken<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "inner", instance("inner", algorithm, concreteType)));

        assertSame(algorithm, dependencies.only("inner", concreteType));

        @SuppressWarnings({"rawtypes", "unchecked"})
        TypeToken<GenericOuter.InnerAlgorithm> rawOwnerType =
                TypeToken.of(GenericOuter.InnerAlgorithm.class);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> dependencies.only("inner", rawOwnerType));

        assertEquals(
                "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "
                        + GenericOuter.InnerAlgorithm.class.getName(),
                failure.getMessage());
    }

    @Test
    void acceptsStaticMemberAlgorithmsOfGenericOwners() {
        GenericOuter.StaticInnerAlgorithm algorithm = new GenericOuter.StaticInnerAlgorithm();
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "inner",
                instance(
                        "inner",
                        algorithm,
                        TypeToken.of(GenericOuter.StaticInnerAlgorithm.class))));

        assertSame(algorithm, dependencies.only("inner", TypeToken.of(GenericOuter.StaticInnerAlgorithm.class)));
    }

    @Test
    void acceptsStaticNestedGenericContractArguments() {
        GenericAlgorithm<Map.Entry<String, String>> algorithm = new GenericAlgorithm<>() {};
        TypeToken<GenericAlgorithm<Map.Entry<String, String>>> type = new TypeToken<>() {};
        AlgorithmDependencies dependencies = new AlgorithmDependencies(Map.of(
                "entry",
                instance("entry", algorithm, type)));

        assertSame(algorithm, dependencies.only("entry", type));
    }

    private interface GenericAlgorithm<VALUE> extends Algorithm {
    }

    private static final class ConcreteStringAlgorithm implements GenericAlgorithm<String> {
    }

    private interface GenericStringAlgorithm<UNUSED> extends GenericAlgorithm<String> {
    }

    private static <UNUSED> AlgorithmInstance<GenericStringAlgorithm<UNUSED>> genericStringInstance(
            GenericStringAlgorithm<UNUSED> algorithm) {
        return instance("strings", algorithm, new TypeToken<>() {});
    }

    private static final class GenericOuter<OWNER> {
        private final class InnerAlgorithm implements Algorithm {
        }

        private static final class StaticInnerAlgorithm implements Algorithm {
        }
    }

    private static <ALGORITHM extends Algorithm> AlgorithmInstance<ALGORITHM> instance(
            String name,
            ALGORITHM algorithm,
            TypeToken<ALGORITHM> algorithmType) {
        return new AlgorithmInstance<>(
                new AlgorithmDefinition(
                        JsonNodeFactory.instance.objectNode(),
                        new AlgorithmId(name, "1"),
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "factory",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                null,
                algorithm,
                algorithmType);
    }

    private static <VALUE> AlgorithmInstance<GenericAlgorithm<VALUE>> genericInstance(
            String name,
            GenericAlgorithm<VALUE> algorithm) {
        return instance(name, algorithm, new TypeToken<>() {});
    }
}
