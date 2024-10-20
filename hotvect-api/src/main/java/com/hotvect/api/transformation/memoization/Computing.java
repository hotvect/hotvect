package com.hotvect.api.transformation.memoization;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.Mapping;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.utils.FuzzyMatch;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;

public class Computing<ARGUMENT> implements Computable {
    private final ARGUMENT ARGUMENT;
    private final NamespacedRecord<Namespace, Holder<Object>> precalculated;
    private final AtomicReference<NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>>> memoized;
    private final AtomicReference<NamespacedRecord<Namespace, Computation<ARGUMENT, Object>>> nonMemoized;

    private Computing(
            ARGUMENT ARGUMENT,
            NamespacedRecord<Namespace, Holder<Object>> precalculated,
            NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoized,
            NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> nonMemoized
    ) {
        this.ARGUMENT = ARGUMENT;
        this.precalculated = precalculated; // Can be null
        this.memoized = new AtomicReference<>(memoized != null ? memoized : NamespacedRecord.empty());
        this.nonMemoized = new AtomicReference<>(nonMemoized != null ? nonMemoized : NamespacedRecord.empty());
    }

    private <V> V suggestAndThrowException(String computationId) {
        Map<? extends Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoizedMap = memoized.get().asMap();
        Map<? extends Namespace, Computation<ARGUMENT, Object>> nonMemoizedMap = nonMemoized.get().asMap();

        Set<String> combinedKeys = Stream.concat(
                Stream.concat(
                        memoizedMap.keySet().stream(),
                        nonMemoizedMap.keySet().stream()
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
    public <V> V computeIfAbsent(Namespace computationId) {
        // First, check if the value is in precalculated
        if (precalculated != null) {
            Holder<Object> precalculatedValue = precalculated.get(computationId);
            if (precalculatedValue != null) {
                return (V) precalculatedValue.value;
            }
        }

        // Check in non-memoized transformations
        Computation<ARGUMENT, Object> transformation = nonMemoized.get().get(computationId);
        if (transformation != null) {
            var context = MemoizationStatistic.startTimer(computationId, false);
            V ret = (V) transformation.apply(this);
            if (context != null) {
                context.close();
            }
            return ret;
        }

        // Check in memoized transformations
        MemoizedValue<Computation<ARGUMENT, Object>, Object> memoizedValue = memoized.get().get(computationId);
        if (memoizedValue == null) {
            // Computation not found, at this point it appears to be an undefined computation
            suggestAndThrowException(computationId.toString());
        }

        // Check for cached value
        Holder<?> cached = memoizedValue.getCache();
        if (cached != null) {
            // Memoized computation was already calculated, use the cached value
            return (V) cached.value;
        }

        // Computation was not performed yet, perform computation and cache the result
        var context = MemoizationStatistic.startTimer(computationId, true);
        V transformed = (V) memoizedValue.getComputation().apply(this);
        if (context != null) {
            context.close();
        }

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
    public <V> V debugComputeIfAbsent(String computationId) {
        Map<? extends Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoizedMap = memoized.get().asMap();
        Map<? extends Namespace, Computation<ARGUMENT, Object>> nonMemoizedMap = nonMemoized.get().asMap();

        // Check in memoized transformations
        for (Namespace columnName : memoizedMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                MemoizedValue<Computation<ARGUMENT, Object>, Object> memoizedValue = memoizedMap.get(columnName);
                if (memoizedValue != null) {
                    Holder<?> holder = memoizedValue.getCache();
                    if (holder != null) {
                        return (V) holder.value;
                    }
                    Computation<ARGUMENT, Object> transformation = memoizedValue.getComputation();
                    V ret = (V) transformation.apply(this);
                    memoizedValue.setCache(new Holder<>(ret));
                    return ret;
                }
            }
        }

        // Check in non-memoized transformations
        for (Namespace columnName : nonMemoizedMap.keySet()) {
            if (columnName.toString().equals(computationId)) {
                Computation<ARGUMENT, Object> transformation = nonMemoizedMap.get(columnName);
                if (transformation != null) {
                    V ret = (V) transformation.apply(this);
                    return ret;
                }
            }
        }

        // Check in precalculated values
        if (precalculated != null) {
            for (Namespace columnName : precalculated.asMap().keySet()) {
                if (columnName.toString().equals(computationId)) {
                    return (V) precalculated.get(columnName);
                }
            }
        }

        return suggestAndThrowException(computationId);
    }


    public ARGUMENT getOriginalInput() {
        return this.ARGUMENT;
    }

    private static <ARGUMENT> Computing<ARGUMENT> memoize(
            ARGUMENT argument,
            NamespacedRecord<? extends Namespace, Holder<Object>> precalculated,
            Mapping<? extends Namespace, Computation<ARGUMENT, Object>> memoized,
            NamespacedRecord<? extends Namespace, Computation<ARGUMENT, Object>> nonMemoized
    ) {
        NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> memoizations;
        if (memoized == null) {
            memoizations = NamespacedRecord.empty();
        } else {
            Namespace[] keys = memoized.keys();
            Computation<ARGUMENT, Object>[] values = memoized.values();
            MemoizedValue<Computation<ARGUMENT, Object>, Object>[] memos = new MemoizedValue[keys.length];
            for (int i = 0; i < keys.length; i++) {
                memos[i] = new MemoizedValue<>(values[i]);
            }
            memoizations = new NamespacedRecordImpl<>(keys, memos);
        }
        if (nonMemoized == null) {
            nonMemoized = NamespacedRecord.empty();
        }

        // 'precalculated' can be null, as it's never added later
        NamespacedRecord<Namespace, Holder<Object>> pre = (NamespacedRecord<Namespace, Holder<Object>>)precalculated;
        NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> nonM = (NamespacedRecord<Namespace, Computation<ARGUMENT, Object>>)nonMemoized;
        return new Computing<>(argument, pre, memoizations, nonM);
    }

    public static <ARGUMENT> Computing<ARGUMENT> withArgument(ARGUMENT argument) {
        return memoize(argument, null, null, null);
    }

    public static <ARGUMENT> Computing<ARGUMENT> withPrecalculations(ARGUMENT argument, Map<? extends Namespace, Object> precalculated) {
        // This method is not performance critical, as addition of pre-calculations are not done in the hot path
        NamespacedRecord<Namespace, Holder<Object>> prec = new NamespacedRecordImpl<>();
        for (Map.Entry<? extends Namespace, Object> entry : precalculated.entrySet()) {
            prec.put(entry.getKey(), new Holder<>(entry.getValue()));
        }
        return memoize(argument, prec, null, null);
    }

    public static <ARGUMENT> Computing<ARGUMENT> withComputations(ARGUMENT argument, Mapping<? extends Namespace, Computation<ARGUMENT, Object>> memoized) {
        return memoize(argument, null, memoized, null);
    }

    public static <ARGUMENT> Computing<ARGUMENT> withComputations(ARGUMENT argument, NamespacedRecord<? extends Namespace, Computation<ARGUMENT, Object>> nonMemoized) {
        return memoize(argument, null, null, nonMemoized);
    }

    public static <ARGUMENT> Computing<ARGUMENT> withComputations(ARGUMENT argument, Mapping<? extends Namespace, Computation<ARGUMENT, Object>> memoized, NamespacedRecord<? extends Namespace, Computation<ARGUMENT, Object>> nonMemoized) {
        return memoize(argument, null, memoized, nonMemoized);
    }

    public Computing<ARGUMENT> appendComputations(
            Mapping<? extends Namespace, Computation<ARGUMENT, Object>> memoized,
            NamespacedRecord<? extends Namespace, Computation<ARGUMENT, Object>> nonMemoized
    ) {
        if (memoized != null && memoized.keys().length > 0) {
            // We have something to memoize, append if not present already
            NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> previous = this.memoized.get();
            NamespacedRecord<Namespace, MemoizedValue<Computation<ARGUMENT, Object>, Object>> newRecord = previous.shallowCopy();
            Namespace[] keys = memoized.keys();
            Computation<ARGUMENT, Object>[] values = memoized.values();
            for (int i = 0; i < keys.length; i++) {
                Namespace computationId = keys[i];
                if (newRecord.get(computationId) == null) {
                    // This transformation is not available in the parent, so add it
                    newRecord.put(computationId, new MemoizedValue<>(values[i]));
                }
            }
            checkState(this.memoized.compareAndSet(previous, newRecord), "Unexpected race condition");
        }

        if (nonMemoized != null && nonMemoized.size() > 0) {
            NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> previous = this.nonMemoized.get();
            NamespacedRecord<Namespace, Computation<ARGUMENT, Object>> newRecord;
            if (previous.size() == 0) {
                newRecord = new NamespacedRecordImpl<>();
            } else {
                newRecord = previous.shallowCopy();
            }
            newRecord.merge(nonMemoized);
            checkState(this.nonMemoized.compareAndSet(previous, newRecord), "Unexpected race condition");
        }

        return this;
    }
}
