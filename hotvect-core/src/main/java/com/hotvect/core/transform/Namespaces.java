package com.hotvect.core.transform;

import com.google.common.base.Joiner;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ValueType;
import com.hotvect.core.util.InvalidTransformationDefinitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * <h2>Namespaces — canonical column handles per class-loader</h2>
 *
 * <p>This class guarantees a <em>single</em> {@link Namespace} instance
 * (one per class-loader) for every distinct <b>textual namespace
 * name</b>. A namespace is conceptually the same as a column handle
 * such as {@code col("clicks")} in Spark SQL, but hotvect lets you
 * construct the handle from a variety of sources:</p>
 *
 * <ul>
 *   <li>Java {@code enum} constants – either <em>legacy</em> enums that
 *       already implement {@link Namespace} <em>(permitted only inside a
 *       composite declaration)</em> or any ordinary enum whose
 *       {@code name()} is copied verbatim (<em>a single constant is
 *       allowed</em>);</li>
 *   <li>other compound namespaces, enabling hierarchical composition
 *       such as {@code declareNamespace(countryNs, dateNs, hourNs)};</li>
 *   <li>a plain string such as {@code "clicks"}.</li>
 * </ul>
 *
 * <p>The first declaration fixes the canonical object; every later call
 * that spells the same textual name returns that exact instance.
 * Singleton handles allow the runtime to rely on constant-time
 * identity-based maps instead of repeatedly hashing and allocating
 * strings.</p>
 *
 * <h3>Creation rules</h3>
 *
 * <ul>
 *   <li><strong>Composite size</strong> – you cannot create a <em>new</em>
 *       namespace out of a single existing namespace; at least two
 *       components are required when declaring via
 *       {@link #declareNamespace(Namespace...)},
 *       {@link #declareNamespace(Class, Namespace...)},
 *       or {@link #declareFeatureNamespace(ValueType, Namespace...)}.</li>
 *   <li><strong>String validator</strong> – plain textual names must
 *       match {@code ^[A-Za-z][A-Za-z0-9_]*$}.</li>
 *   <li><strong>Return-type hint</strong>
 *       <ul>
 *         <li>May be supplied only on the <em>first</em> declaration of
 *             a string-based namespace.</li>
 *         <li>If the namespace was first declared without a hint you
 *             cannot add one later.</li>
 *         <li>Calling the overload <em>without</em> a hint on an
 *             already-hinted namespace is allowed.</li>
 *       </ul></li>
 *   <li><strong>Feature value type</strong> – to treat a namespace as a
 *       feature column you <em>must</em> provide a non-null
 *       {@link ValueType} on first declaration. Changing or adding a
 *       value type afterwards throws
 *       {@link IllegalArgumentException}.</li>
 *   <li><strong>Feature namespace retrieval</strong> – once a name has
 *       been declared as a <em>feature</em> namespace it can only be
 *       retrieved through
 *       {@link #declareFeatureNamespace(ValueType, String)} or
 *       {@link #declareFeatureNamespace(ValueType, Namespace...)} with
 *       the identical {@link ValueType}; any plain
 *       {@code declareNamespace} overload or a different value type
 *       triggers {@link IllegalArgumentException}.</li>
 *   <li><strong>Legacy-enum identity</strong> – an enum constant whose
 *       type itself implements {@link Namespace} is keyed by its
 *       <em>identity</em>; therefore two distinct legacy-enum namespaces
 *       may share the same textual name yet remain distinct. All other
 *       namespace sources collapse purely by name.</li>
 * </ul>
 *
 * <h3>Lifecycle and thread safety</h3>
 *
 * <p>The factory methods are <strong>not</strong> intended for
 * performance-critical paths at inference time, but they are
 * <strong>thread-safe</strong>: every access to the internal registry
 * is guarded by a dedicated lock object. Declare each namespace once
 * during pipeline initialisation, <strong>cache the returned
 * singleton</strong> (for example in a {@code static} field) and reuse
 * it on hot paths. Singletons live as long as their defining
 * <strong>class-loader</strong>; once the loader is unloaded, all
 * cached objects are eligible for GC.</p>
 *
 * <h3>Deprecated helpers</h3>
 *
 * <p>{@link #getNamespace(Namespace...)} and
 * {@link #getFeatureNamespace(ValueType, Namespace...)} remain only for
 * source compatibility and will be removed in a future release.</p>
 */
public final class Namespaces {

    private static final Logger log = LoggerFactory.getLogger(Namespaces.class);
    private static final AtomicInteger WARN_COUNT = new AtomicInteger(0);

    /** Global lock guarding {@link #NAME_REGISTER}. */
    private static final Object LOCK = new Object();

    /** Textual namespace → singleton instance. */
    private static final Map<String, Namespace> NAME_REGISTER = new HashMap<>();

    /** First character must be a letter, subsequent characters may be letters, digits or underscores. */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private Namespaces() { }

    /* ───────────────────────────── declareNamespace (enum / composite) ───────────────────────────── */

    public static Namespace declareNamespace(Class<?> returnTypeHint, Namespace... namespaces) {

        /* ---------- input validation ---------- */
        if (namespaces == null || namespaces.length == 0) {
            throw new InvalidTransformationDefinitionException("Namespaces must not be null or empty");
        }

        /* ===================================================================== */
        /* === single component: use / register the supplied object directly === */
        /* ===================================================================== */
        if (namespaces.length == 1) {
            Namespace candidate = namespaces[0];
            validateName(candidate.toString());
            warnOnHotLoop();

            synchronized (LOCK) {
                return NAME_REGISTER.compute(candidate.toString(), (k, existing) -> {

                    /* first encounter – store caller’s object as canonical */
                    if (existing == null) {
                        /* basic consistency check against optional hint      */
                        if (returnTypeHint != null && candidate.getReturnTypeHint() != null && !returnTypeHint.equals(candidate.getReturnTypeHint())) {
                            throw new IllegalArgumentException(String.format("Attempted to set returnTypeHint to %s, but candidate already declares %s for namespace %s", returnTypeHint, candidate.getReturnTypeHint(), k));
                        }
                        return candidate;
                    }

                    /* already registered – validate compatibility */
                    if (returnTypeHint != null && existing.getReturnTypeHint() != null && !returnTypeHint.equals(existing.getReturnTypeHint())) {
                        throw new IllegalArgumentException(String.format("Attempted to set returnTypeHint to %s, but it was already set to %s for namespace %s", returnTypeHint, existing.getReturnTypeHint(), existing));
                    }

                    if (existing.getFeatureValueType() != null && candidate.getFeatureValueType() != null && !existing.getFeatureValueType().equals(candidate.getFeatureValueType())) {
                        throw new IllegalArgumentException(String.format("Attempted to redeclare feature namespace %s with a different ValueType (%s vs %s)", k, existing.getFeatureValueType(), candidate.getFeatureValueType()));
                    }

                    /* compatible – keep the previously registered singleton */
                    return existing;
                });
            }
        }

        /* ================================================================ */
        /* === composite namespace (two or more components) – old logic === */
        /* ================================================================ */
        String joinedName = Joiner.on('_').join(Arrays.stream(namespaces).map(Object::toString).toList());

        Namespace result;
        synchronized (LOCK) {
            result = NAME_REGISTER.compute(joinedName, (k, existing) -> {
                if (existing == null) {
                    return new NamespaceId(k, returnTypeHint, null);
                }
                if (existing instanceof NamespaceId nid) {
                    if (returnTypeHint != null && nid.returnTypeHint() != null && !returnTypeHint.equals(nid.returnTypeHint())) {
                        throw new IllegalArgumentException(String.format("Attempted to set returnTypeHint to %s, but it was already set to %s for namespace %s", returnTypeHint, nid.returnTypeHint(), nid));
                    }
                    return nid;
                } else if (existing instanceof FeatureNamespaceId fid) {
                    if (returnTypeHint != null && !returnTypeHint.equals(fid.getReturnTypeHint())) {
                        throw new IllegalArgumentException(String.format("Attempted to set returnTypeHint to %s, but it was already set to %s for namespace %s", returnTypeHint, fid.featureValueType().getJavaType(), fid));
                    }
                    return fid;
                }
                throw new IllegalArgumentException("Namespace name \"" + k + "\" already bound to a feature namespace");
            });
        }

        warnOnHotLoop();
        return result;
    }

    public static Namespace declareNamespace(Namespace... namespaces) {
        return declareNamespace(null, namespaces);
    }

    /* ───────────────────────────── string / enum declarations ───────────────────────────── */

    public static Namespace declareNamespace(String namespaceName) {
        validateName(namespaceName);
        warnOnHotLoop();
        synchronized (LOCK) {
            return NAME_REGISTER.computeIfAbsent(namespaceName,
                    n -> new NamespaceId(n, null, null));
        }
    }

    public static Namespace declareNamespace(Enum<?> constant) {
        validateName(constant.name());
        warnOnHotLoop();
        synchronized (LOCK) {
            if (constant instanceof Namespace) {
                Namespace previous =  NAME_REGISTER.computeIfAbsent(constant.name(),
                        n -> (Namespace) constant);
                if (previous != constant) {
                    throw new InvalidTransformationDefinitionException("Namespace name \"" + constant.name() + " of class " + constant.getDeclaringClass().getCanonicalName()
                            + "\" already bound to a different namespace: " + previous + " of class " + previous.getClass().getCanonicalName());
                }
                return previous;
            } else {
                return NAME_REGISTER.computeIfAbsent(constant.name(),
                        n -> new NamespaceId(n, null, null));
            }
        }
    }

    public static Namespace declareNamespace(Class<?> returnTypeHint, String namespaceName) {
        validateName(namespaceName);
        warnOnHotLoop();

        synchronized (LOCK) {
            return NAME_REGISTER.compute(namespaceName, (k, existing) -> {
                if (existing == null) {
                    return new NamespaceId(k, returnTypeHint, null);
                }
                if (existing instanceof NamespaceId nid) {
                    if (nid.returnTypeHint() == null) {
                        throw new IllegalArgumentException(
                                "Namespace \"" + k + "\" was declared without returnTypeHint; cannot add one later");
                    }
                    if (!nid.returnTypeHint().equals(returnTypeHint)) {
                        throw new IllegalArgumentException(String.format(
                                "Attempted to set returnTypeHint to %s, but it was already set to %s for namespace %s",
                                returnTypeHint, nid.returnTypeHint(), nid));
                    }
                    return nid;
                }
                throw new IllegalArgumentException(
                        "Namespace name \"" + k + "\" already bound to a feature namespace");
            });
        }
    }

    /* ───────────────────────────── declareFeatureNamespace ───────────────────────────── */

    public static Namespace declareFeatureNamespace(ValueType featureValueType, Namespace... namespaces) {
        if (namespaces == null || namespaces.length == 0) {
            throw new InvalidTransformationDefinitionException("Namespaces must not be null or empty");
        }
        if(featureValueType == null) {
            throw new InvalidTransformationDefinitionException("Feature value type cannot be null");
        }
        checkArgument(namespaces.length >= 2,
                "A composite feature namespace must contain at least two components");

        String joinedName = Joiner.on('_').join(
                Arrays.stream(namespaces).map(Object::toString).toList());

        Namespace singleton;
        synchronized (LOCK) {
            singleton = NAME_REGISTER.compute(joinedName, (k, existing) -> {
                if (existing == null) {
                    return new FeatureNamespaceId(k, featureValueType);
                }
                if (existing instanceof FeatureNamespaceId fid) {
                    if (!fid.featureValueType().equals(featureValueType)) {
                        throw new IllegalArgumentException(String.format(
                                "Attempted to set featureValueType to %s, but it was already set to %s for namespace %s",
                                featureValueType, fid.featureValueType(), fid));
                    }
                    return fid;
                }
                throw new IllegalArgumentException(
                        "Namespace name \"" + k + "\" already bound to a plain namespace");
            });
        }

        warnOnHotLoop();
        return singleton;
    }

    public static Namespace declareFeatureNamespace(ValueType featureValueType, String namespaceName) {
        if(featureValueType == null) {
            throw new InvalidTransformationDefinitionException("Feature value type cannot be null");
        }
        validateName(namespaceName);
        warnOnHotLoop();

        synchronized (LOCK) {
            return NAME_REGISTER.compute(namespaceName, (k, existing) -> {
                if (existing == null) {
                    return new FeatureNamespaceId(k, featureValueType);
                }
                if (existing instanceof FeatureNamespaceId fid) {
                    if (!fid.featureValueType().equals(featureValueType)) {
                        throw new IllegalArgumentException(String.format(
                                "Attempted to set featureValueType to %s, but it was already set to %s for namespace %s",
                                featureValueType, fid.featureValueType(), fid));
                    }
                    return fid;
                }
                throw new IllegalArgumentException(
                        "Namespace name \"" + k + "\" already bound to a plain namespace");
            });
        }
    }

    /* ───────────────────────────── optional feature namespace ───────────────────────────── */
    /**
     * Declares a feature namespace <em>iff</em> {@code featureValueType} is
     * non-null; otherwise falls back to {@link #declareNamespace(Namespace...)}
     * and returns a plain namespace.
     */
    public static Namespace tryDeclareFeatureNamespace(ValueType featureValueType,
                                                       Namespace... namespaces) {
        return featureValueType == null
                ? declareNamespace(namespaces)
                : declareFeatureNamespace(featureValueType, namespaces);
    }

    /**
     * String-based variant of {@link #tryDeclareFeatureNamespace(ValueType, Namespace...)}.
     */
    public static Namespace tryDeclareFeatureNamespace(ValueType featureValueType,
                                                       String namespaceName) {
        return featureValueType == null
                ? declareNamespace(namespaceName)
                : declareFeatureNamespace(featureValueType, namespaceName);
    }

    /* ───────────────────────────── deprecated helpers ───────────────────────────── */

    @Deprecated(forRemoval = true)
    public static Namespace getNamespace(Namespace... namespaces) {
        checkArgument(namespaces != null && namespaces.length >= 2,
                "A composite namespace must contain at least two components");
        return declareNamespace(namespaces);
    }

    @Deprecated(forRemoval = true)
    public static FeatureNamespace getFeatureNamespace(ValueType featureValueType, Namespace... namespaces) {
        Objects.requireNonNull(featureValueType, "Feature value type cannot be null");
        checkArgument(namespaces != null && namespaces.length >= 2,
                "A composite feature namespace must contain at least two components");
        return (FeatureNamespace) declareFeatureNamespace(featureValueType, namespaces);
    }

    /* ───────────────────────────── internal namespace records ───────────────────────────── */

    public record NamespaceId(String namespaceName,
                              Class<?> returnTypeHint,
                              ValueType featureValueType) implements Namespace {

        @Override public Class<?>  getReturnTypeHint()   { return returnTypeHint; }
        @Override public ValueType getFeatureValueType() { return featureValueType; }
        @Override public String    toString()            { return namespaceName; }
    }

    public record FeatureNamespaceId(String namespaceName,
                                     ValueType featureValueType) implements Namespace, FeatureNamespace {

        @Override public Class<?>  getReturnTypeHint()   { return featureValueType.getJavaType(); }
        @Override public ValueType getFeatureValueType() { return featureValueType; }
        @Override public String    toString()            { return namespaceName; }
    }

    /* ───────────────────────────── helpers ───────────────────────────── */

    private static void warnOnHotLoop() {
        int calls = WARN_COUNT.incrementAndGet();
        int warnAt = 50_000;
        if (calls > warnAt && calls % warnAt == 0) {
            log.warn("Namespace declaration methods are called very frequently ({} times) - this could indicate a bug. "
                    + " You must cache Namespace handle (objects) into fields etc. You may not obtain (declare) them at inference time.", calls);
        }
    }

    private static void validateName(String name) {
        Objects.requireNonNull(name, "Name cannot be null");
        checkArgument(VALID_NAME.matcher(name).matches(),
                "Namespace name must match " + VALID_NAME.pattern());
    }

    /** <b>TEST-ONLY</b> – clears the internal registry and warning counters. */
    static void clear() {
        synchronized (LOCK) {
            NAME_REGISTER.clear();
        }
        WARN_COUNT.set(0);
    }
}
