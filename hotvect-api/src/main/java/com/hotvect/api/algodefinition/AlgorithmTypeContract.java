package com.hotvect.api.algodefinition;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;

/** Validation for complete algorithm type contracts. */
public final class AlgorithmTypeContract {
    private AlgorithmTypeContract() {
    }

    /** Requires a declared algorithm type without raw or wildcard components. */
    public static <ALGORITHM extends Algorithm> TypeToken<ALGORITHM> requireDeclared(
            TypeToken<ALGORITHM> algorithmType) {
        TypeToken<ALGORITHM> resolved = Objects.requireNonNull(
                algorithmType,
                "algorithmType must not be null");
        if (!Algorithm.class.isAssignableFrom(resolved.getRawType())) {
            throw new IllegalArgumentException(
                    "algorithmType must describe an Algorithm type: " + resolved);
        }
        if (!isDeclared(resolved.getType())) {
            throw new IllegalArgumentException(
                    "algorithmType must be an algorithm contract without raw or wildcard types: " + resolved);
        }
        return resolved;
    }

    /** Requires a fully resolved algorithm type without raw, wildcard, or type-variable components. */
    public static <ALGORITHM extends Algorithm> TypeToken<ALGORITHM> requireFullyResolved(
            TypeToken<ALGORITHM> algorithmType) {
        TypeToken<ALGORITHM> resolved = Objects.requireNonNull(
                algorithmType,
                "algorithmType must not be null");
        if (!Algorithm.class.isAssignableFrom(resolved.getRawType())) {
            throw new IllegalArgumentException(
                    "algorithmType must describe an Algorithm type: " + resolved);
        }
        if (!isConcrete(resolved.getType())) {
            throw new IllegalArgumentException(
                    "algorithmType must be fully resolved without raw, wildcard, or type-variable components: "
                            + resolved);
        }
        return resolved;
    }

    static <ALGORITHM extends Algorithm> TypeToken<ALGORITHM> fromConcreteClass(
            Class<ALGORITHM> algorithmType) {
        Objects.requireNonNull(algorithmType, "algorithmType must not be null");
        if (algorithmType.getTypeParameters().length != 0) {
            throw new IllegalArgumentException(
                    "Generic algorithm type " + algorithmType.getName()
                            + " requires a TypeToken with concrete type arguments");
        }
        return requireFullyResolved(TypeToken.of(algorithmType));
    }

    private static boolean isDeclared(Type type) {
        if (type instanceof Class<?> typeClass) {
            return typeClass.isArray()
                    ? isDeclared(typeClass.getComponentType())
                    : typeClass.getTypeParameters().length == 0
                            && !hasUnresolvedGenericOwner(typeClass);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            if (!(parameterizedType.getRawType() instanceof Class<?> rawType)) {
                return false;
            }
            Type ownerType = parameterizedType.getOwnerType();
            return (ownerType == null || Modifier.isStatic(rawType.getModifiers()) || isDeclared(ownerType))
                    && Arrays.stream(parameterizedType.getActualTypeArguments())
                            .allMatch(AlgorithmTypeContract::isDeclared);
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return isDeclared(genericArrayType.getGenericComponentType());
        }
        return type instanceof java.lang.reflect.TypeVariable<?>;
    }

    static boolean isConcrete(Type type) {
        if (type instanceof Class<?> typeClass) {
            return typeClass.isArray()
                    ? isConcrete(typeClass.getComponentType())
                    : typeClass.getTypeParameters().length == 0
                            && !hasUnresolvedGenericOwner(typeClass);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            if (!(parameterizedType.getRawType() instanceof Class<?> rawType)) {
                return false;
            }
            Type ownerType = parameterizedType.getOwnerType();
            return (ownerType == null || Modifier.isStatic(rawType.getModifiers()) || isConcrete(ownerType))
                    && Arrays.stream(parameterizedType.getActualTypeArguments())
                            .allMatch(AlgorithmTypeContract::isConcrete);
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return isConcrete(genericArrayType.getGenericComponentType());
        }
        return false;
    }

    private static boolean hasUnresolvedGenericOwner(Class<?> typeClass) {
        if (Modifier.isStatic(typeClass.getModifiers()) || !typeClass.isMemberClass()) {
            return false;
        }
        Class<?> owner = typeClass.getDeclaringClass();
        return owner.getTypeParameters().length != 0 || hasUnresolvedGenericOwner(owner);
    }
}
