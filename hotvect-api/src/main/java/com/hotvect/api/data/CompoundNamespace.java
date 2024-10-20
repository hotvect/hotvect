package com.hotvect.api.data;

import com.google.common.base.Joiner;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * CompoundNamespace is a class that represents a composite of namespaces,
 * allowing for hierarchical representation of namespaces and feature namespaces.
 * It provides methods to retrieve or create Namespace and FeatureNamespace instances
 * based on a sequence of Namespace enums or composite namespaces.
 *
 * Note: This class is not thread-safe. Do not use methods in this class concurrently
 * in multiple threads without external synchronization.
 */
@NotThreadSafe
public class CompoundNamespace {
    private static final Map<List<Class<? extends Namespace>>, CompoundNamespace> DEFINED_FACTORIES = new HashMap<>();
    private final EnumMap<?, ?> index;

    private CompoundNamespace(boolean isFeature, List<Class<? extends Namespace>> namespaceClasses) {
        checkAllEnums(namespaceClasses);
        checkArgument(
                !DEFINED_FACTORIES.containsKey(namespaceClasses),
                "A Namespace factory for %s has already been defined. Namespace factories must be singletons for each unique sequence of Namespace classes.",
                namespaceClasses
        );
        this.index = createIndex(isFeature, namespaceClasses.toArray(new Class[0]));
    }

    /**
     * Please do not use this method while you are performing inference as it is not thread-safe nor performant.
     * Instead, store the Namespace object in a field during constructing the transformer and use the cached
     * value during inference.
     *
     * @param namespaces Sequence of Namespaces (must include at least two).
     * @return A Namespace instance representing the composite of the provided namespaces.
     * @throws IllegalArgumentException if fewer than two namespaces are provided or if the provided namespaces result in a single flattened namespace.
     */
    public static Namespace declareNamespace(Namespace... namespaces) {
        Objects.requireNonNull(namespaces, "Namespaces cannot be null");
        checkArgument(namespaces.length >= 2, "At least two namespaces are required to create a composite namespace");

        List<Namespace> flattenedNamespaces = flattenNamespaces(namespaces);
        checkArgument(flattenedNamespaces.size() >= 2, "Cannot create a composite namespace from a single namespace");

        List<Class<? extends Namespace>> factoryKey = flattenedNamespaces.stream()
                .map(Namespace::getClass)
                .collect(Collectors.toList());

        CompoundNamespace factory = DEFINED_FACTORIES.computeIfAbsent(factoryKey, key -> new CompoundNamespace(false, key));
        return factory.get(flattenedNamespaces.toArray(new Namespace[0]));
    }

    /**
     * Please do not use this method while you are performing inference as it is not thread-safe nor performant.
     * Instead, store the Namespace object in a field during constructing the transformer and use this cached
     * value during inference.
     *
     * A FeatureNamespace can only have one feature value type. If you create a FeatureNamespace with a feature value
     * type, and then later attempt to retrieve the same FeatureNamespace with a different feature value type, an exception
     * is thrown.
     *
     * @param featureValueType The ValueType associated with the feature.
     * @param namespaces       Sequence of Namespaces (must include at least two).
     * @return A FeatureNamespace instance representing the composite of the provided namespaces and associated with the given ValueType.
     * @throws IllegalArgumentException if fewer than two namespaces are provided or if the provided namespaces result in a single flattened namespace.
     */
    public static FeatureNamespace declareFeatureNamespace(ValueType featureValueType, Namespace... namespaces) {
        Objects.requireNonNull(featureValueType, "Feature value type cannot be null");
        Objects.requireNonNull(namespaces, "Namespaces cannot be null");
        checkArgument(namespaces.length >= 2, "At least two namespaces are required to create a composite feature namespace");

        List<Namespace> flattenedNamespaces = flattenNamespaces(namespaces);
        checkArgument(flattenedNamespaces.size() >= 2, "Cannot create a composite namespace from a single namespace");

        List<Class<? extends Namespace>> factoryKey = flattenedNamespaces.stream()
                .map(Namespace::getClass)
                .collect(Collectors.toList());

        CompoundNamespace factory = DEFINED_FACTORIES.computeIfAbsent(factoryKey, key -> new CompoundNamespace(true, key));
        Namespace namespace = factory.get(flattenedNamespaces.toArray(new Namespace[0]));

        checkArgument(
                namespace instanceof FeatureNamespace,
                "The namespace %s was already declared as a non-feature namespace. Each sequence of Namespace classes can only correspond to either a plain Namespace or a FeatureNamespace. You cannot mix them.",
                namespace
        );

        FeatureNamespaceId featureNamespace = (FeatureNamespaceId) namespace;
        if (featureNamespace.getFeatureValueType() == null) {
            featureNamespace.setFeatureValueType(featureValueType);
        } else {
            checkArgument(
                    featureNamespace.getFeatureValueType() == featureValueType,
                    "Attempted to retrieve a FeatureNamespace with ValueType %s, but this namespace was already assigned ValueType %s. Offending namespace: %s",
                    featureValueType,
                    featureNamespace.getFeatureValueType(),
                    namespace
            );
        }

        return featureNamespace;
    }

    /**
     * @deprecated This method is deprecated and scheduled for removal.
     * Use {@link #declareNamespace(Namespace... namespaces)} instead.
     *
     * Please do not use this method while you are performing inference as it is not thread-safe nor performant.
     * Instead, store the Namespace object in a field during constructing the transformer and use the cached
     * value during inference.
     *
     * @param namespaces Sequence of Namespaces (must include at least two).
     * @return A Namespace instance representing the composite of the provided namespaces.
     * @throws IllegalArgumentException if fewer than two namespaces are provided.
     */
    @Deprecated(forRemoval = true)
    public static Namespace getNamespace(Namespace... namespaces) {
        checkArgument(namespaces != null && namespaces.length >= 2, "At least two namespaces are required to create a composite namespace");
        return declareNamespace(namespaces);
    }

    /**
     * @deprecated This method is deprecated and scheduled for removal.
     * Use {@link #declareFeatureNamespace(ValueType featureValueType, Namespace... namespaces)} instead.
     *
     * Please do not use this method while you are performing inference as it is not thread-safe nor performant.
     * Instead, store the Namespace object in a field during constructing the transformer and use this cached
     * value during inference.
     *
     * A FeatureNamespace can only have one feature value type. If you create a FeatureNamespace with a feature value
     * type, and then later attempt to retrieve the same FeatureNamespace with a different feature value type, an exception
     * is thrown.
     *
     * @param featureValueType The ValueType associated with the feature.
     * @param namespaces       Sequence of Namespaces (must include at least two).
     * @return A FeatureNamespace instance representing the composite of the provided namespaces and associated with the given ValueType.
     * @throws IllegalArgumentException if fewer than two namespaces are provided.
     */
    @Deprecated(forRemoval = true)
    public static FeatureNamespace getFeatureNamespace(ValueType featureValueType, Namespace... namespaces) {
        Objects.requireNonNull(featureValueType, "Feature value type cannot be null");
        checkArgument(namespaces != null && namespaces.length >= 2, "At least two namespaces are required to create a composite feature namespace");
        return declareFeatureNamespace(featureValueType, namespaces);
    }

    /** Rest of the class remains unchanged **/

    /**
     * Creates an index map that represents the hierarchical structure of the namespaces.
     *
     * @param isFeature       Indicates whether the namespaces are feature namespaces.
     * @param namespaceClasses Array of Namespace classes.
     * @return An EnumMap representing the namespace hierarchy.
     */
    private EnumMap<?, ?> createIndex(boolean isFeature, Class<? extends Namespace>[] namespaceClasses) {
        checkArgument(namespaceClasses.length > 0, "At least one namespace is required");
        EnumMap enumMap = new EnumMap(namespaceClasses[0]);
        for (Enum enumConstant : (Enum[]) namespaceClasses[0].getEnumConstants()) {
            enumMap.put(enumConstant, createNestedMap(isFeature, namespaceClasses, 1, (Namespace) enumConstant));
        }
        return enumMap;
    }

    /**
     * Recursively creates nested maps for the namespace hierarchy.
     *
     * @param isFeature       Indicates whether the namespaces are feature namespaces.
     * @param namespaceClasses Array of Namespace classes.
     * @param level           Current depth level in the hierarchy.
     * @param currentPath     Array of Namespace instances representing the current path.
     * @return An EnumMap representing the nested namespace hierarchy.
     */
    private EnumMap<?, ?> createNestedMap(boolean isFeature, Class<? extends Namespace>[] namespaceClasses, int level, Namespace... currentPath) {
        if (level >= namespaceClasses.length) {
            throw new IllegalStateException("Level exceeds the number of namespaces");
        }
        EnumMap nestedMap = new EnumMap(namespaceClasses[level]);
        for (Enum enumConstant : (Enum[]) namespaceClasses[level].getEnumConstants()) {
            Namespace[] newPath = Arrays.copyOf(currentPath, level + 1);
            newPath[level] = (Namespace) enumConstant;
            if (level == namespaceClasses.length - 1) {
                if (!isFeature) {
                    nestedMap.put(enumConstant, new NamespaceId(newPath));
                } else {
                    nestedMap.put(enumConstant, new FeatureNamespaceId(newPath));
                }
            } else {
                nestedMap.put(enumConstant, createNestedMap(isFeature, namespaceClasses, level + 1, newPath));
            }
        }
        return nestedMap;
    }

    /**
     * Represents a composite namespace identifier.
     */
    public static class NamespaceId implements Namespace {
        private static final Joiner UNDERSCORE_JOINER = Joiner.on('_');
        private final Namespace[] namespaces;
        private final String namespaceName;

        private NamespaceId(Namespace[] namespaces) {
            this.namespaces = Arrays.copyOf(namespaces, namespaces.length);
            this.namespaceName = UNDERSCORE_JOINER.join(namespaces);
        }

        @Override
        public String toString() {
            return namespaceName;
        }

        public Namespace[] getNamespaces() {
            return namespaces;
        }
    }

    /**
     * Represents a composite feature namespace identifier with an associated ValueType.
     */
    public static class FeatureNamespaceId extends NamespaceId implements FeatureNamespace {
        private ValueType featureValueType;

        private FeatureNamespaceId(Namespace[] namespaces) {
            super(namespaces);
        }

        @Override
        public ValueType getFeatureValueType() {
            return this.featureValueType;
        }

        public void setFeatureValueType(ValueType featureValueType) {
            this.featureValueType = featureValueType;
        }
    }

    /**
     * Checks that all provided Namespace classes are enums.
     *
     * @param namespaceClasses List of Namespace classes.
     */
    private static void checkAllEnums(List<Class<? extends Namespace>> namespaceClasses) {
        checkArgument(
                namespaceClasses.stream().allMatch(Class::isEnum),
                "All Namespace classes must be enums. Found the following classes that are not enums: %s",
                namespaceClasses.stream().filter(clazz -> !clazz.isEnum()).collect(Collectors.toList())
        );
    }

    /**
     * Retrieves the Namespace instance based on the provided sequence of namespaces.
     *
     * @param namespaces Sequence of Namespaces.
     * @return The Namespace instance corresponding to the sequence.
     */
    public Namespace get(Namespace... namespaces) {
        Map<Enum<?>, Object> currentMap = (Map<Enum<?>, Object>) this.index;
        for (int i = 0; i < namespaces.length - 1; i++) {
            currentMap = (Map<Enum<?>, Object>) currentMap.get(namespaces[i]);
            if (currentMap == null) {
                throw new IllegalArgumentException("Invalid namespace path: " + Arrays.toString(namespaces));
            }
        }
        return (Namespace) currentMap.get(namespaces[namespaces.length - 1]);
    }

    /**
     * Recursively flattens an array of Namespaces, expanding any composite namespaces into their components.
     *
     * @param namespaces Sequence of Namespaces which may include composite namespaces.
     * @return A list of Namespaces with all composite namespaces expanded.
     */
    private static List<Namespace> flattenNamespaces(Namespace... namespaces) {
        List<Namespace> result = new ArrayList<>();
        for (Namespace ns : namespaces) {
            if (ns instanceof NamespaceId) {
                NamespaceId namespaceId = (NamespaceId) ns;
                result.addAll(flattenNamespaces(namespaceId.getNamespaces()));
            } else {
                result.add(ns);
            }
        }
        return result;
    }

    /**
     * This method is intended for testing purposes only. Do not use it in production code.
     */
    static void clear() {
        DEFINED_FACTORIES.clear();
    }
}