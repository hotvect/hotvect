package com.hotvect.onlineutils.serving;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmTypeContract;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import java.util.List;

/** Contract shared by fixed and EMS offline prediction roots. */
final class OfflineServingRootValidator {
    private OfflineServingRootValidator() {
    }

    static void validate(String rootDescription, AlgorithmInstance<?> instance) {
        invocationContract(rootDescription, instance);
    }

    static void validateCompatible(
            String rootDescription,
            List<AlgorithmInstance<?>> variants) {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException(rootDescription + " must have at least one active variant");
        }
        AlgorithmInstance<?> expectedInstance = variants.getFirst();
        TypeToken<? extends Algorithm> expected = invocationContract(rootDescription, expectedInstance);
        for (AlgorithmInstance<?> candidate : variants.subList(1, variants.size())) {
            TypeToken<? extends Algorithm> actual = invocationContract(rootDescription, candidate);
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        rootDescription + " variants must share one invocation contract; "
                                + expectedInstance.algorithmDefinition().algorithmId().value() + " declares " + expected
                                + " but " + candidate.algorithmDefinition().algorithmId().value() + " declares " + actual);
            }
        }
    }

    private static TypeToken<? extends Algorithm> invocationContract(
            String rootDescription,
            AlgorithmInstance<?> instance) {
        Class<?> declaredType = instance.algorithmType().getRawType();
        if (Ranker.class.isAssignableFrom(declaredType)) {
            return resolvedSupertype(instance.algorithmType(), Ranker.class);
        }
        if (BulkScorer.class.isAssignableFrom(declaredType)) {
            return resolvedSupertype(instance.algorithmType(), BulkScorer.class);
        }
        if (ThemedTopK.class.isAssignableFrom(declaredType)) {
            return resolvedSupertype(instance.algorithmType(), ThemedTopK.class);
        }
        if (TopK.class.isAssignableFrom(declaredType)) {
            return resolvedSupertype(instance.algorithmType(), TopK.class);
        }
        throw new IllegalArgumentException(
                rootDescription + " must implement Ranker, BulkScorer, TopK, or ThemedTopK; got "
                        + instance.algorithmType());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TypeToken<? extends Algorithm> resolvedSupertype(
            TypeToken<? extends Algorithm> declaredType,
            Class<? extends Algorithm> invocationType) {
        TypeToken resolved = ((TypeToken) declaredType).getSupertype(invocationType);
        return AlgorithmTypeContract.requireFullyResolved(resolved);
    }
}
