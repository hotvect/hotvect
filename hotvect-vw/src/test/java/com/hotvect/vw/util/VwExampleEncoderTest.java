package com.hotvect.vw.util;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.scoring.ScoringExample;
import com.hotvect.api.data.RawValue;
import com.hotvect.core.audit.AuditableScoringVectorizer;
import com.hotvect.core.audit.RawFeatureName;
import com.hotvect.vw.VwScoringExampleEncoder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VwExampleEncoderTest {

    @Test
    void nonBinaryWithoutWeights() {
        double targetVariable = 99.1;
        SparseVector featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
        String expected = "99.1 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, false);
    }

    @Test
    void binaryWithoutWeights() {
        double targetVariable = 99.1;
        SparseVector featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
        String expected = "1 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, true);

        targetVariable = 0;
        expected = "-1 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, true);

    }

    @Test
    void withWeights() {
        double targetVariable = 99.1;
        SparseVector featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
        String expected = "1 198.2 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, true, d -> d * 2);
    }

    private void testEncoding(double targetVariable, SparseVector featureVector, String expected, boolean binary) {
        testEncoding(targetVariable, featureVector, expected, binary, null);
    }


    private void testEncoding(double targetVariable, SparseVector featureVector, String expected, boolean binary, ToDoubleFunction<Double> weightFun) {
        DataRecord<TestRawNamespace, RawValue> testRecord = new DataRecord<TestRawNamespace, RawValue>(TestRawNamespace.class);
        VwScoringExampleEncoder<DataRecord<TestRawNamespace, RawValue>, Double> subject;
        if (weightFun == null) {
            subject = new VwScoringExampleEncoder<>(
                    new AuditableScoringVectorizer<>() {
                        @Override
                        public SparseVector apply(DataRecord<TestRawNamespace, RawValue> toVectorize) {
                            return featureVector;
                        }

                        @Override
                        public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit() {
                            throw new AssertionError("not implemented");
                        }

                    },
                    d -> d,
                    binary);
        } else {
            subject = new VwScoringExampleEncoder<>(
                    new AuditableScoringVectorizer<>() {
                        @Override
                        public ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit() {
                            throw new AssertionError("not implemented");
                        }

                        @Override
                        public SparseVector apply(DataRecord<TestRawNamespace, RawValue> toVectorize) {
                            return featureVector;
                        }
                    },
                    d -> d,
                    binary,
                    weightFun
            );
        }
        String encoded = subject.apply(new ScoringExample<>(testRecord, targetVariable));
        assertEquals(expected, encoded);
    }
}