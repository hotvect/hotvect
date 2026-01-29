package com.hotvect.api.data;

import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * CompoundNamespace is a class that represents a composite of namespaces,
 * allowing for hierarchical representation of namespaces and feature namespaces.
 * It provides methods to retrieve or create Namespace instances
 * based on a sequence of Namespace enums or composite namespaces.
 *
 * Note: This class is not thread-safe. Do not use methods in this class concurrently
 * in multiple threads without external synchronization.
 *
 * @deprecated Scheduled for removal. Use Namespaces in hotvect-core instead.
 */
@Deprecated(forRemoval = true)
public class CompoundNamespace {
    private static final Logger log = LoggerFactory.getLogger(CompoundNamespace.class);
    private static final AtomicInteger WARN_COUNT = new AtomicInteger(0);
    private static final Map<List<Namespace>, Namespace> NAMESPACE_REGISTER = new HashMap<>();

    public static Namespace declareNamespace(Class<?> returnTypeHint, Namespace... namespaces) {
        Objects.requireNonNull(namespaces, "Namespaces cannot be null");
        checkArgument(namespaces.length >= 2, "At least two namespaces are required to create a composite namespace");
        List<Namespace> flattenedNamespaces = flattenNamespaces(namespaces);
        checkArgument(flattenedNamespaces.size() >= 2, "Cannot create a composite namespace from a single namespace");

        NamespaceId newNamespaceId = new NamespaceId(flattenedNamespaces.toArray(new Namespace[0]), returnTypeHint, null);
        Namespace result = NAMESPACE_REGISTER.putIfAbsent(flattenedNamespaces, newNamespaceId);
        if (result == null) {
            result = newNamespaceId;
        } else {
            if (result instanceof NamespaceId namespaceId) {
                if (namespaceId.returnTypeHint == null) {
                    namespaceId.returnTypeHint = returnTypeHint;
                } else if (returnTypeHint != null && !namespaceId.returnTypeHint.equals(returnTypeHint)) {
                    throw new IllegalArgumentException(String.format(
                            "Attempted to set returnTypeHint to %s, but it was already set to %s for namespace %s",
                            returnTypeHint, namespaceId.returnTypeHint, namespaceId
                    ));
                }
            } else {
                throw new AssertionError("Did not expect anything other than NamespaceId:" + result.getClass().getCanonicalName());
            }
        }

        int callcount = WARN_COUNT.incrementAndGet();
        if (callcount > 20 * 1000 && callcount % 1000 == 0) {
            log.warn("declareNamespace or declareFeatureNamespace is being called many times ({}). This may indicate a bug. Namespaces should be cached after declaration.", callcount);
        }
        return result;
    }

    public static Namespace declareNamespace(Namespace... namespaces) {
        return declareNamespace(null, namespaces);
    }

    public static Namespace declareFeatureNamespace(ValueType featureValueType, Namespace... namespaces) {
        Objects.requireNonNull(featureValueType, "Feature value type cannot be null");
        Objects.requireNonNull(namespaces, "Namespaces cannot be null");
        checkArgument(namespaces.length >= 2, "At least two namespaces are required to create a composite feature namespace");
        List<Namespace> flattenedNamespaces = flattenNamespaces(namespaces);
        checkArgument(flattenedNamespaces.size() >= 2, "Cannot create a composite namespace from a single namespace");

        NamespaceId newNamespaceId = new NamespaceId(flattenedNamespaces.toArray(new Namespace[0]), null, featureValueType);
        Namespace result = NAMESPACE_REGISTER.putIfAbsent(flattenedNamespaces, newNamespaceId);
        if (result == null) {
            result = newNamespaceId;
            newNamespaceId.returnTypeHint = featureValueType.getJavaType();
        } else {
            if (result instanceof NamespaceId namespaceId) {
                if (namespaceId.featureValueType == null) {
                    namespaceId.featureValueType = featureValueType;
                    if (namespaceId.returnTypeHint == null) {
                        namespaceId.returnTypeHint = featureValueType.getJavaType();
                    } else if (!namespaceId.returnTypeHint.equals(featureValueType.getJavaType())) {
                        throw new IllegalArgumentException(String.format(
                                "Attempted to set featureValueType to %s (Java type %s) but returnTypeHint was already set to %s for namespace %s",
                                featureValueType, featureValueType.getJavaType(), namespaceId.returnTypeHint, namespaceId
                        ));
                    }
                } else if (!namespaceId.featureValueType.equals(featureValueType)) {
                    throw new IllegalArgumentException(String.format(
                            "Attempted to set featureValueType to %s, but it was already set to %s for namespace %s",
                            featureValueType, namespaceId.featureValueType, namespaceId
                    ));
                }
            } else {
                throw new AssertionError("Did not expect anything other than NamespaceId:" + result.getClass().getCanonicalName());
            }
        }

        int callcount = WARN_COUNT.incrementAndGet();
        if (callcount > 20 * 1000 && callcount % 1000 == 0) {
            log.warn("declareNamespace or declareFeatureNamespace is being called many times ({}). This may indicate a bug. Namespaces should be cached after declaration.", callcount);
        }
        return result;
    }

    @Deprecated(forRemoval = true)
    public static Namespace getNamespace(Namespace... namespaces) {
        checkArgument(namespaces != null && namespaces.length >= 2, "At least two namespaces are required to create a composite namespace");
        return declareNamespace(namespaces);
    }

    @Deprecated(forRemoval = true)
    public static FeatureNamespace getFeatureNamespace(ValueType featureValueType, Namespace... namespaces) {
        Objects.requireNonNull(featureValueType, "Feature value type cannot be null");
        checkArgument(namespaces != null && namespaces.length >= 2, "At least two namespaces are required to create a composite feature namespace");

        List<Namespace> flattenedNamespaces = flattenNamespaces(namespaces);
        checkArgument(flattenedNamespaces.size() >= 2, "Cannot create a composite namespace from a single namespace");

        Namespace result;
        synchronized (NAMESPACE_REGISTER) {
            result = NAMESPACE_REGISTER.get(flattenedNamespaces);
            if (result == null) {
                FeatureNamespaceId newFeatureNamespaceId = new FeatureNamespaceId(flattenedNamespaces.toArray(new Namespace[0]), featureValueType);
                NAMESPACE_REGISTER.put(flattenedNamespaces, newFeatureNamespaceId);
                result = newFeatureNamespaceId;
            } else {
                if (result instanceof FeatureNamespaceId featureNamespaceId) {
                    if (featureNamespaceId.featureValueType == null) {
                        featureNamespaceId.featureValueType = featureValueType;
                        if (featureNamespaceId.returnTypeHint == null) {
                            featureNamespaceId.returnTypeHint = featureValueType.getJavaType();
                        } else if (!featureNamespaceId.returnTypeHint.equals(featureValueType.getJavaType())) {
                            throw new IllegalArgumentException(String.format(
                                    "Attempted to set featureValueType to %s (Java type %s) but returnTypeHint was already set to %s for namespace %s",
                                    featureValueType, featureValueType.getJavaType(), featureNamespaceId.returnTypeHint, featureNamespaceId
                            ));
                        }
                    } else if (!featureNamespaceId.featureValueType.equals(featureValueType)) {
                        throw new IllegalArgumentException(String.format(
                                "Attempted to set featureValueType to %s, but it was already set to %s for namespace %s",
                                featureValueType, featureNamespaceId.featureValueType, featureNamespaceId
                        ));
                    }
                } else if (result instanceof NamespaceId namespaceId) {
                    throw new IllegalArgumentException(String.format(
                            "The namespace %s was already declared as a non-feature namespace. Each sequence of Namespace classes can only correspond to either a plain Namespace or a FeatureNamespace. You cannot mix them.",
                            result
                    ));
                } else {
                    throw new AssertionError("Did not expect anything other than NamespaceId or FeatureNamespaceId:" + result.getClass().getCanonicalName());
                }
            }
        }

        int callcount = WARN_COUNT.incrementAndGet();
        if (callcount > 20 * 1000 && callcount % 1000 == 0) {
            log.warn("getFeatureNamespace is being called many times ({}). This may indicate a bug. Namespaces should be cached after declaration.", callcount);
        }

        return (FeatureNamespace) result;
    }

    public static class NamespaceId implements Namespace {
        private static final Joiner UNDERSCORE_JOINER = Joiner.on('_');
        private final Namespace[] namespaces;
        private final String namespaceName;
        protected Class<?> returnTypeHint;
        protected ValueType featureValueType;

        private NamespaceId(Namespace[] namespaces, Class<?> returnTypeHint, ValueType featureValueType) {
            this.namespaces = Arrays.copyOf(namespaces, namespaces.length);
            this.namespaceName = UNDERSCORE_JOINER.join(namespaces);
            this.returnTypeHint = returnTypeHint;
            this.featureValueType = featureValueType;
        }

        @Override
        public String toString() {
            return namespaceName;
        }

        @Override
        public Namespace[] getComponents() {
            return Arrays.copyOf(namespaces, namespaces.length);
        }

        @Override
        public Class<?> getReturnTypeHint() {
            return returnTypeHint;
        }

        @Override
        public ValueType getFeatureValueType() {
            return featureValueType;
        }

        @Deprecated
        public Namespace[] getNamespaces() {
            return getComponents();
        }
    }

    public static class FeatureNamespaceId extends NamespaceId implements FeatureNamespace {
        private FeatureNamespaceId(Namespace[] namespaces, ValueType featureValueType) {
            super(namespaces, null, featureValueType);
            this.returnTypeHint = featureValueType.getJavaType();
        }

        @Override
        public Class<?> getReturnTypeHint() {
            return featureValueType.getJavaType();
        }
    }

    private static List<Namespace> flattenNamespaces(Namespace... namespaces) {
        List<Namespace> result = new ArrayList<>();
        for (Namespace ns : namespaces) {
            if (ns instanceof NamespaceId namespaceId) {
                result.addAll(flattenNamespaces(namespaceId.getComponents()));
            } else {
                result.add(ns);
            }
        }
        return result;
    }

    static void clear() {
        NAMESPACE_REGISTER.clear();
    }
}
