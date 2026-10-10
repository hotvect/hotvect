package com.hotvect.onlineutils.hotdeploy;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Verifies class identity for every contract type crossing one artifact-loader boundary. */
final class DomainModelBoundaryValidator {
    private DomainModelBoundaryValidator() {
    }

    /**
     * Validates that the invoking artifact sees every dependency contract type as the same class.
     *
     * <p>The declared type token was captured from the dependency's factory when the algorithm was
     * constructed, so graph traversal does not need the dependency's artifact classloader.</p>
     */
    static void validateEdge(
            String parentAlgorithmName,
            ClassLoader parentClassLoader,
            String dependencyName,
            TypeToken<? extends Algorithm> dependencyContract) {
        Objects.requireNonNull(parentAlgorithmName, "parentAlgorithmName must not be null");
        Objects.requireNonNull(parentClassLoader, "parentClassLoader must not be null");
        Objects.requireNonNull(dependencyName, "dependencyName must not be null");
        Objects.requireNonNull(dependencyContract, "dependencyContract must not be null");
        String edge = parentAlgorithmName + "." + dependencyName;
        boundaryTypes(dependencyContract).forEach(
                type -> requireSameTypeFromInvoker(edge, parentClassLoader, type));
    }

    private static Set<Type> boundaryTypes(TypeToken<? extends Algorithm> algorithmType) {
        LinkedHashSet<Type> result = new LinkedHashSet<>();
        if (algorithmType.getRawType().isInterface()) {
            result.add(algorithmType.getType());
        }
        for (TypeToken<?> interfaceContract : algorithmType.getTypes().interfaces()) {
            Class<?> rawType = interfaceContract.getRawType();
            if (rawType != Algorithm.class && Algorithm.class.isAssignableFrom(rawType)) {
                result.add(interfaceContract.getType());
            }
        }
        return Set.copyOf(result);
    }

    private static void requireSameTypeFromInvoker(
            String edge,
            ClassLoader parentClassLoader,
            Type contractType) {
        if (contractType instanceof Class<?> typeClass) {
            if (typeClass.isArray()) {
                requireSameTypeFromInvoker(edge, parentClassLoader, typeClass.getComponentType());
            } else {
                requireSameClassFromInvoker(edge, parentClassLoader, typeClass);
            }
            return;
        }
        if (contractType instanceof ParameterizedType parameterizedType) {
            requireSameTypeFromInvoker(edge, parentClassLoader, parameterizedType.getRawType());
            Type ownerType = parameterizedType.getOwnerType();
            if (ownerType != null) {
                requireSameTypeFromInvoker(edge, parentClassLoader, ownerType);
            }
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                requireSameTypeFromInvoker(edge, parentClassLoader, argument);
            }
            return;
        }
        if (contractType instanceof GenericArrayType genericArrayType) {
            requireSameTypeFromInvoker(edge, parentClassLoader, genericArrayType.getGenericComponentType());
            return;
        }
        if (contractType instanceof TypeVariable<?>) {
            return;
        }
        throw new IllegalStateException("Unsupported resolved algorithm contract type: " + contractType);
    }

    private static void requireSameClassFromInvoker(
            String edge,
            ClassLoader parentClassLoader,
            Class<?> contractType) {
        if (contractType.isPrimitive() || contractType.getClassLoader() == null) {
            return;
        }
        Class<?> asSeenByInvoker;
        try {
            asSeenByInvoker = parentClassLoader.loadClass(contractType.getName());
        } catch (ClassNotFoundException error) {
            throw notShared(edge, contractType, "the invoking artifact cannot load it", error);
        }
        if (asSeenByInvoker != contractType) {
            throw notShared(
                    edge,
                    contractType,
                    "the invoking artifact loads a different copy from " + asSeenByInvoker.getClassLoader(),
                    null);
        }
    }

    private static IllegalStateException notShared(
            String edge,
            Class<?> contractType,
            String reason,
            ClassNotFoundException cause) {
        return new IllegalStateException(
                "Algorithm dependency " + edge + " crosses an artifact boundary and invokes "
                        + contractType.getName() + " loaded from " + contractType.getClassLoader()
                        + ", but " + reason + ". Domain model classes shared across artifacts must be"
                        + " supplied by their common parent classloader.",
                cause);
    }
}
