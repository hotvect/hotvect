package com.eshioji.hotvect.core.score;

import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.core.TestRawNamespace;
import com.eshioji.hotvect.core.vectorization.Vectorizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScorerImplTest {

    @Test
    void applyAsDouble() {
        SparseVector expected = new SparseVector(new int[]{1,2,3}, new double[]{1.0, 2.0, 3.0});
        Vectorizer<TestRawNamespace> vectorizer = _x -> expected;
        Estimator estimator = featureVector -> {
            assertEquals(expected, featureVector);
            return 9.9;
        };

        ScorerImpl<TestRawNamespace> subject = new ScorerImpl<>(vectorizer, estimator);
        assertEquals(9.9, subject.applyAsDouble(null));
    }
}