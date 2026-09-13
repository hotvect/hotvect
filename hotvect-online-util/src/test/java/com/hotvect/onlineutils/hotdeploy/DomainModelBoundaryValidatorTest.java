package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.data.ranking.RankingResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainModelBoundaryValidatorTest {

    @Test
    void rejectsSiblingLoaderScorerDomainTypes() throws Exception {
        TypeToken<? extends Algorithm> contract = contractFrom(
                IsolatedScorerFactory.class,
                PrivateDomain.class);
        ClassLoader invokingLoader = isolatedLoader(PrivateDomain.class);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> DomainModelBoundaryValidator.validateEdge(
                        "parent",
                        invokingLoader,
                        "child",
                        contract));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains(PrivateDomain.class.getName()));
    }

    @Test
    void rejectsNestedGenericDomainTypesFromSiblingLoaders() throws Exception {
        TypeToken<? extends Algorithm> contract = contractFrom(
                IsolatedNestedRankerFactory.class,
                PrivateDomain.class);
        ClassLoader invokingLoader = isolatedLoader(PrivateDomain.class);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> DomainModelBoundaryValidator.validateEdge(
                        "parent",
                        invokingLoader,
                        "child",
                        contract));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains(PrivateDomain.class.getName()));
    }

    @Test
    void acceptsDomainTypesOwnedByTheCommonParentLoader() throws Exception {
        ClassLoader dependencyLoader = isolatedLoader(ParentOwnedScorerFactory.class);
        TypeToken<? extends Algorithm> contract = AlgorithmContractResolver.resolve(
                dependencyLoader.loadClass(ParentOwnedScorerFactory.class.getName()));

        assertDoesNotThrow(() -> DomainModelBoundaryValidator.validateEdge(
                "parent",
                getClass().getClassLoader(),
                "child",
                contract));
    }

    @Test
    void acceptsGenericFactoryContractsWithoutInventingDomainTypes() {
        TypeToken<? extends Algorithm> contract = AlgorithmContractResolver.resolve(GenericRankerFactory.class);

        assertDoesNotThrow(() -> DomainModelBoundaryValidator.validateEdge(
                "parent",
                getClass().getClassLoader(),
                "child",
                contract));
    }

    private static TypeToken<? extends Algorithm> contractFrom(Class<?> factory, Class<?> privateDomain)
            throws Exception {
        ClassLoader dependencyLoader = isolatedLoader(factory, privateDomain);
        return AlgorithmContractResolver.resolve(dependencyLoader.loadClass(factory.getName()));
    }

    private static ClassLoader isolatedLoader(Class<?>... classes) throws IOException {
        Map<String, byte[]> definitions = new HashMap<>();
        for (Class<?> type : classes) {
            definitions.put(type.getName(), classBytes(type));
        }
        return new ByteArrayClassLoader(DomainModelBoundaryValidatorTest.class.getClassLoader(), definitions);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Class bytes not found: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        private ByteArrayClassLoader(ClassLoader parent, Map<String, byte[]> definitions) {
            super(parent);
            this.definitions = Map.copyOf(definitions);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = definitions.get(name);
                    loaded = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    public static final class PrivateDomain {
    }

    public static final class SharedAction {
    }

    public static final class IsolatedScorerFactory implements SimpleAlgorithmFactory<Scorer<PrivateDomain>> {
        @Override
        @SuppressWarnings("removal")
        public Scorer<PrivateDomain> apply(Optional<JsonNode> hyperparameter) {
            return record -> 0.0;
        }
    }

    public static final class IsolatedNestedRankerFactory
            implements SimpleRankerFactory<List<PrivateDomain>, SharedAction> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<List<PrivateDomain>, SharedAction> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }

    public static final class ParentOwnedScorerFactory implements SimpleAlgorithmFactory<Scorer<PrivateDomain>> {
        @Override
        @SuppressWarnings("removal")
        public Scorer<PrivateDomain> apply(Optional<JsonNode> hyperparameter) {
            return record -> 0.0;
        }
    }

    public static final class GenericRankerFactory<SHARED, ACTION>
            implements SimpleRankerFactory<SHARED, ACTION> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<SHARED, ACTION> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }
}
