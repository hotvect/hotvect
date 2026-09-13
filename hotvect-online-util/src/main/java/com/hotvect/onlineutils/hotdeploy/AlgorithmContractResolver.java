package com.hotvect.onlineutils.hotdeploy;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmTypeContract;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import com.hotvect.api.algorithms.Algorithm;
import java.util.Objects;

/** Extracts and caches declared algorithm contracts from factory classes. */
final class AlgorithmContractResolver {
    private static final ClassValue<TypeToken<? extends Algorithm>> CONTRACTS = new ClassValue<>() {
        @Override
        protected TypeToken<? extends Algorithm> computeValue(Class<?> factoryClass) {
            return extract(factoryClass);
        }
    };

    private AlgorithmContractResolver() {
    }

    /**
     * Returns the cached contract declared by a loaded factory class.
     *
     * <p>Contracts may retain type variables. A composite factory can check an individual dependency's
     * interface and fully resolved generic contract by passing a concrete {@code TypeToken} to
     * {@code AlgorithmDependencies.only(...)}; unresolved generic arguments are allowed without
     * verifying generic compatibility. Raw and wildcard contracts are rejected because
     * they do not describe a usable algorithm contract.</p>
     */
    static TypeToken<? extends Algorithm> resolve(Class<?> factoryClass) {
        return CONTRACTS.get(Objects.requireNonNull(factoryClass, "factoryClass must not be null"));
    }

    private static TypeToken<? extends Algorithm> extract(Class<?> factoryClass) {
        TypeToken<?> declaredAlgorithm = declaredAlgorithmType(factoryClass);
        if (!Algorithm.class.isAssignableFrom(declaredAlgorithm.getRawType())) {
            throw new IllegalArgumentException(
                    "Factory " + factoryClass.getName()
                            + " does not declare an Algorithm return type: " + declaredAlgorithm);
        }

        @SuppressWarnings("unchecked")
        TypeToken<? extends Algorithm> declaredAlgorithmContract =
                (TypeToken<? extends Algorithm>) declaredAlgorithm;
        return AlgorithmTypeContract.requireDeclared(declaredAlgorithmContract);
    }

    private static TypeToken<?> declaredAlgorithmType(Class<?> factoryClass) {
        TypeToken<?> factoryType = TypeToken.of(factoryClass);
        if (NonCompositeAlgorithmFactory.class.isAssignableFrom(factoryClass)) {
            return factoryType.resolveType(NonCompositeAlgorithmFactory.class.getTypeParameters()[1]);
        }
        if (CompositeAlgorithmFactory.class.isAssignableFrom(factoryClass)) {
            return factoryType.resolveType(CompositeAlgorithmFactory.class.getTypeParameters()[0]);
        }
        if (SimpleAlgorithmFactory.class.isAssignableFrom(factoryClass)) {
            return factoryType.resolveType(SimpleAlgorithmFactory.class.getTypeParameters()[0]);
        }
        if (NonCompositeStateFactory.class.isAssignableFrom(factoryClass)) {
            return factoryType.resolveType(NonCompositeStateFactory.class.getTypeParameters()[0]);
        }
        throw new IllegalArgumentException(
                "Factory " + factoryClass.getName() + " does not implement a supported Hotvect factory SPI");
    }

}
