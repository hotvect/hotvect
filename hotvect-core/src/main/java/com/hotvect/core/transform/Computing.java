package com.hotvect.core.transform;

import com.google.common.base.MoreObjects;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.utils.ArrayTransform;
import com.hotvect.utils.FuzzyMatch;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Computing<ARGUMENT> implements Computable<ARGUMENT> {
    private final ARGUMENT ARGUMENT;
    private final NamespacedRecord<Namespace, Holder<Object>> precalculated;
    private final AtomicReference<NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>>> memoized;
    private final AtomicReference<NamespacedRecord<Namespace, Computation<ARGUMENT, Object>>> onDemand;

    protected Computing(
            ARGUMENT ARGUMENT,
            NamespacedRecord<Namespace, Holder<Object>> precalculated,
            NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoized,
            NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> onDemand
    ) {
        this.ARGUMENT = ARGUMENT;
        this.precalculated = precalculated; // Can be null
        this.memoized = new AtomicReference<>(memoized != null ? memoized : NamespacedRecord.empty());
        this.onDemand = new AtomicReference<>(onDemand != null ? onDemand : NamespacedRecord.empty());
    }

    private <V> V suggestAndThrowException(String computationId) {
        Map<? extends Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoizedMap = memoized.get().asMap();
        Map<? extends Namespace, Computation<ARGUMENT, Object>> onDemandMap = onDemand.get().asMap();
        Set<String> combinedKeys = Stream.concat(
                Stream.concat(
                        memoizedMap.keySet().stream(),
                        onDemandMap.keySet().stream()
                ),
                precalculated != null ? precalculated.asMap().keySet().stream() : Stream.empty()
        ).map(Namespace::toString).collect(Collectors.toSet());
        FuzzyMatch fuzzyMatch = new FuzzyMatch(combinedKeys);
        List<String> closestMatch = fuzzyMatch.getClosestCandidates(computationId);
        throw new WrongTransformationDefinitionException(
                String.format(
                        "The requested computation %s cannot be found. Did you mean: %s?",
                        computationId,
                        closestMatch
                )
        );
    }

    @Override
    public <V> V compute(Namespace computationId) {
        // First, check if the value is in precalculated
        if (precalculated != null) {
            Holder<Object> precalculatedValue = precalculated.get(computationId);
            if (precalculatedValue != null) {
                return (V) precalculatedValue.value();
            }
        }

        // Check in onDemand transformations
        Computation<ARGUMENT, Object> transformation = onDemand.get().get(computationId);
        if (transformation != null) {
            V ret = (V) transformation.apply(this);
            return ret;
        }

        // Check in memoized transformations
        MemoizedValue<Computation<ARGUMENT, Object>, Object> memoizedValue = memoized.get().get(computationId);
        if (memoizedValue == null) {
            // Computation not found, at this point it appears to be an undefined computation
            return suggestAndThrowException(computationId.toString());
        }

        // Check for cached value
        Holder<?> cached = memoizedValue.getCache();
        if (cached != null) {
            // Memoized computation was already calculated, use the cached value
            return (V) cached.value();
        }

        // Computation was not performed yet, perform computation and cache the result
        V transformed = (V) memoizedValue.getComputation().apply(this);
        memoizedValue.setCache(new Holder<>(transformed));
        return transformed;
    }

    /**
     * Please only use this method for debugging purposes, it is very inefficient
     *
     * @param computationId
     * @return
     * @param <V>
     */
    @Override
    public <V> V debugCompute(String computationId) {
        Map<? extends Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoizedMap = memoized.get().asMap();
        Map<? extends Namespace, Computation<ARGUMENT, Object>> onDemandMap = onDemand.get().asMap();

        // Check in memoized transformations
        for (Namespace columnName : memoizedMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                MemoizedValue<Computation<ARGUMENT, Object>, Object> memoizedValue = memoizedMap.get(columnName);
                if (memoizedValue != null) {
                    Holder<?> holder = memoizedValue.getCache();
                    if (holder != null) {
                        Object value = holder.value();
                        return castWithTypeHint(value, columnName);
                    }
                    Computation<ARGUMENT, Object> transformation = memoizedValue.getComputation();
                    Object ret = transformation.apply(this);
                    memoizedValue.setCache(new Holder<>(ret));
                    return castWithTypeHint(ret, columnName);
                }
            }
        }

        // Check in onDemand transformations
        for (Namespace columnName : onDemandMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                Computation<ARGUMENT, Object> transformation = onDemandMap.get(columnName);
                if (transformation != null) {
                    Object ret = transformation.apply(this);
                    return castWithTypeHint(ret, columnName);
                }
            }
        }

        // Check in precalculated values
        if (precalculated != null) {
            for (Namespace columnName : precalculated.asMap().keySet()) {
                if (columnName.toString().equals(computationId)) {
                    Holder<Object> value = precalculated.get(columnName);
                    return castWithTypeHint(value.value(), columnName);
                }
            }
        }

        return suggestAndThrowException(computationId);
    }


    private static Class<?> wrap(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            if (clazz == int.class) return Integer.class;
            if (clazz == long.class) return Long.class;
            if (clazz == double.class) return Double.class;
            if (clazz == float.class) return Float.class;
            if (clazz == boolean.class) return Boolean.class;
            if (clazz == char.class) return Character.class;
            if (clazz == short.class) return Short.class;
            if (clazz == byte.class) return Byte.class;
        }
        return clazz;
    }

    /**
     * Please only use this method for debugging
     */
    private <V> V castWithTypeHint(Object value, Namespace namespace) {
        Class<?> returnTypeHint = namespace.getReturnTypeHint();
        if (returnTypeHint != null) {
            Class<?> wrappedType = wrap(returnTypeHint);
            if (!wrappedType.isInstance(value)) {
                String message = "Value of computation " + namespace + " is of type " + value.getClass().getName()
                        + " but expected return type is " + returnTypeHint.getName() + " of value:" + MoreObjects.toStringHelper(value);
                throw new WrongTransformationDefinitionException(message);
            }
        }
        return (V) value;
    }

    @Override
    public ARGUMENT getOriginalInput() {
        return this.ARGUMENT;
    }

    /**
     * Given an ongoing computation, append additional memoized and onDemand computations to it.
     * The call is append-only, and appending of a computation is idempotent. Finally, the caller always attempts to
     * append all the necessary computations in its context, and thus it is safe to set the new set of computations (rather than
     * performing a compare-and-set).
     * @param memoized
     * @param onDemand
     * @return
     */
    @Override
    public Computing<ARGUMENT> appendComputations(
            Mapping<Namespace, Computation<ARGUMENT, Object>> memoized,
            Mapping<Namespace, Computation<ARGUMENT, Object>> onDemand
    ) {
        if (memoized != null && memoized.keys().length > 0) {
            // We have something to memoize, append if not present already
            Namespace[] keys = memoized.keys();
            Computation<ARGUMENT, Object>[] values = memoized.values();
            NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> previous = this.memoized.get();
            NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> newRecord = previous.shallowCopy();
            MemoizedValue<Computation<ARGUMENT, Object>, Object>[] memoizedValues = ArrayTransform.map(values, MemoizedValue::new, MemoizedValue[]::new);
            boolean hasUpdate = newRecord.putAllIfAbsent(keys, memoizedValues);
            if(hasUpdate){
                this.memoized.set(newRecord);
            }
        }
        if (onDemand != null && onDemand.keys().length > 0) {
            NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> previous = this.onDemand.get();
            NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> newRecord = previous.shallowCopy();
            Namespace[] keys = onDemand.keys();
            Computation<ARGUMENT, Object>[] values = onDemand.values();
            boolean hasUpdate = newRecord.putAllIfAbsent(keys, values);
            if(hasUpdate){
                this.onDemand.set(newRecord);
            }
        }
        return this;
    }

    /**
     * Builder pattern for Computing class
     */
    public static <ARGUMENT> Builder<ARGUMENT> builder(ARGUMENT argument) {
        return new Builder<>(argument);
    }

    public static class Builder<ARGUMENT> {
        private final ARGUMENT argument;
        private NamespacedRecord<Namespace, Holder<Object>> precalculated;
        private Mapping<Namespace, Computation<ARGUMENT, Object>> memoized;
        private NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> onDemand;

        private Builder(ARGUMENT argument) {
            this.argument = argument;
        }

        public Builder<ARGUMENT> withPrecalculated(NamespacedRecord<Namespace, Holder<Object>> precalculated) {
            this.precalculated = precalculated;
            return this;
        }

        public Builder<ARGUMENT> withMemoizedComputations(Mapping<Namespace, Computation<ARGUMENT, Object>> memoized) {
            this.memoized = memoized;
            return this;
        }

        public Builder<ARGUMENT> withOnDemandComputations(NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> onDemand) {
            this.onDemand = onDemand;
            return this;
        }

        public Computing<ARGUMENT> build() {
            // Create the NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoizations
            NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoizations;
            if (memoized == null) {
                memoizations = NamespacedRecord.empty();
            } else {
                Namespace[] keys = memoized.keys();
                Computation<ARGUMENT, Object>[] values = memoized.values();
                IdentityHashMap<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> map = new IdentityHashMap<>(keys.length);
                for (int i = 0; i < keys.length; i++) {
                    map.put(keys[i], new MemoizedValue<>(values[i]));
                }
                memoizations = new NamespacedRecordImpl<>(map);
            }
            if (onDemand == null) {
                onDemand = NamespacedRecord.empty();
            }
            return new Computing<>(argument, precalculated, memoizations, onDemand);
        }
    }
}
