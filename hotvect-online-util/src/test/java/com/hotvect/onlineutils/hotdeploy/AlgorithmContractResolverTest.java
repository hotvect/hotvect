package com.hotvect.onlineutils.hotdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.data.ranking.RankingResponse;
import java.lang.reflect.TypeVariable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmContractResolverTest {

    @Test
    void resolvesAndCachesAConcreteFactoryContract() {
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> first =
                AlgorithmContractResolver.resolve(ConcreteRankerFactory.class);
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> second =
                AlgorithmContractResolver.resolve(ConcreteRankerFactory.class);

        assertSame(first, second);
        assertEquals(new TypeToken<Ranker<Shared, Action>>() {}, first);
    }

    @Test
    void resolvesConcreteTypesThroughAGenericFactoryBaseClass() {
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> contract =
                AlgorithmContractResolver.resolve(SubclassedRankerFactory.class);

        assertEquals(new TypeToken<Ranker<Shared, Action>>() {}, contract);
    }

    @Test
    void retainsAFactoryContractThatUsesTypeVariables() {
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> contract =
                AlgorithmContractResolver.resolve(UnresolvedRankerFactory.class);

        assertEquals(
                List.of("S", "A"),
                java.util.Arrays.stream(((java.lang.reflect.ParameterizedType) contract.getType())
                                .getActualTypeArguments())
                        .map(TypeVariable.class::cast)
                        .map(TypeVariable::getName)
                        .toList());
    }

    @Test
    void retainsScorerAndNestedGenericContractTypes() {
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> scorer =
                AlgorithmContractResolver.resolve(ScorerFactory.class);
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> nested =
                AlgorithmContractResolver.resolve(NestedRankerFactory.class);

        assertEquals(new TypeToken<Scorer<PrivateDomain>>() {}, scorer);
        assertEquals(new TypeToken<Ranker<List<PrivateDomain>, Action>>() {}, nested);
    }

    @Test
    void retainsCustomGenericAlgorithmContracts() {
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> contract =
                AlgorithmContractResolver.resolve(CustomAlgorithmFactory.class);

        assertEquals(new TypeToken<CustomAlgorithm<PrivateDomain>>() {}, contract);
    }

    @Test
    void retainsAConcreteAlgorithmTypeDeclaredByTheFactory() {
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> contract =
                AlgorithmContractResolver.resolve(ConcreteCustomAlgorithmFactory.class);

        assertEquals(TypeToken.of(ConcreteCustomAlgorithm.class), contract);
    }

    @Test
    void requiresConcreteOwnersForNonStaticMemberAlgorithmContracts() {
        IllegalArgumentException rawOwnerFailure = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmContractResolver.resolve(RawOwnerAlgorithmFactory.class));
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> concreteOwnerContract =
                AlgorithmContractResolver.resolve(ConcreteOwnerAlgorithmFactory.class);

        assertTrue(rawOwnerFailure.getMessage().contains(GenericOwner.InnerAlgorithm.class.getName()));
        assertEquals(
                new TypeToken<GenericOwner<PrivateDomain>.InnerAlgorithm>() {},
                concreteOwnerContract);
    }

    @Test
    void acceptsStaticNestedGenericContractArguments() {
        TypeToken<? extends com.hotvect.api.algorithms.Algorithm> contract =
                AlgorithmContractResolver.resolve(StaticNestedGenericRankerFactory.class);

        assertEquals(
                new TypeToken<Ranker<Map.Entry<String, String>, Action>>() {},
                contract);
    }

    public record Shared(String value) {
    }

    public record Action(String value) {
    }

    public static final class ConcreteRankerFactory implements SimpleRankerFactory<Shared, Action> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<Shared, Action> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }

    public static class GenericRankerFactory<S, A>
            implements SimpleAlgorithmFactory<Ranker<S, A>> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<S, A> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }

    public static final class SubclassedRankerFactory extends GenericRankerFactory<Shared, Action> {
    }

    public static final class UnresolvedRankerFactory<S, A>
            implements SimpleAlgorithmFactory<Ranker<S, A>> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<S, A> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }

    public static final class ScorerFactory implements SimpleAlgorithmFactory<Scorer<PrivateDomain>> {
        @Override
        @SuppressWarnings("removal")
        public Scorer<PrivateDomain> apply(Optional<JsonNode> hyperparameter) {
            return record -> 0.0;
        }
    }

    public static final class NestedRankerFactory
            implements SimpleRankerFactory<List<PrivateDomain>, Action> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<List<PrivateDomain>, Action> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }

    public static final class StaticNestedGenericRankerFactory
            implements SimpleRankerFactory<Map.Entry<String, String>, Action> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<Map.Entry<String, String>, Action> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }

    public interface CustomAlgorithm<VALUE> extends com.hotvect.api.algorithms.Algorithm {
    }

    public static final class CustomAlgorithmFactory
            implements SimpleAlgorithmFactory<CustomAlgorithm<PrivateDomain>> {
        @Override
        @SuppressWarnings("removal")
        public CustomAlgorithm<PrivateDomain> apply(Optional<JsonNode> hyperparameter) {
            return new CustomAlgorithm<>() {};
        }
    }

    public static final class ConcreteCustomAlgorithm implements CustomAlgorithm<PrivateDomain> {
    }

    public static final class ConcreteCustomAlgorithmFactory
            implements SimpleAlgorithmFactory<ConcreteCustomAlgorithm> {
        @Override
        @SuppressWarnings("removal")
        public ConcreteCustomAlgorithm apply(Optional<JsonNode> hyperparameter) {
            return new ConcreteCustomAlgorithm();
        }
    }

    public static final class GenericOwner<OWNER> {
        public final class InnerAlgorithm implements com.hotvect.api.algorithms.Algorithm {
        }
    }

    @SuppressWarnings("rawtypes")
    public static final class RawOwnerAlgorithmFactory
            implements SimpleAlgorithmFactory<GenericOwner.InnerAlgorithm> {
        @Override
        public GenericOwner.InnerAlgorithm apply(Optional<JsonNode> hyperparameter) {
            return new GenericOwner<PrivateDomain>().new InnerAlgorithm();
        }
    }

    public static final class ConcreteOwnerAlgorithmFactory
            implements SimpleAlgorithmFactory<GenericOwner<PrivateDomain>.InnerAlgorithm> {
        @Override
        public GenericOwner<PrivateDomain>.InnerAlgorithm apply(Optional<JsonNode> hyperparameter) {
            return new GenericOwner<PrivateDomain>().new InnerAlgorithm();
        }
    }

    public static final class PrivateDomain {
    }
}
