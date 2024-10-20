package com.hotvect.core.score;

import com.hotvect.api.algodefinition.scoring.ScoringVectorizer;
import com.hotvect.api.data.SparseVector;
import com.hotvect.core.TestRawNamespace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScorerImplTest {

    @Test
    void applyAsDouble() {
        SparseVector expected = new SparseVector(new int[]{1,2,3}, new double[]{1.0, 2.0, 3.0});
        ScoringVectorizer<TestRawNamespace> scoringVectorizer = _x -> expected;
        Estimator estimator = featureVector -> {
            assertEquals(expected, featureVector);
            return 9.9;
        };

        ScorerImpl<TestRawNamespace> subject = new ScorerImpl<>(scoringVectorizer, estimator);
        assertEquals(9.9, subject.applyAsDouble(null));
    }
}