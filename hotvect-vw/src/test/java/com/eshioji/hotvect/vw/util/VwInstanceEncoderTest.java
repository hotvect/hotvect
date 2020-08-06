package com.eshioji.hotvect.vw.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.vw.VwInstanceEncoder;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VwInstanceEncoderTest {

    @Test
    void nonBinaryWithoutWeights() {
        var targetVariable = 99.1;
        var featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
        var expected = "99.1 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, false);
    }

    @Test
    void binaryWithoutWeights() {
        var targetVariable = 99.1;
        var featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
        var expected = "1 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, true);

        targetVariable = 0;
        expected = "-1 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, true);

    }

    @Test
    void withWeights() {
        var targetVariable = 99.1;
        var featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
        var expected = "1 198.2 | 1:1 2:2 3:3.3456 ";
        testEncoding(targetVariable, featureVector, expected, true, d -> d * 2);
    }

    private void testEncoding(double targetVariable, SparseVector featureVector, String expected, boolean binary) {
        testEncoding(targetVariable, featureVector, expected, binary, null);
    }


    private void testEncoding(double targetVariable, SparseVector featureVector, String expected, boolean binary, DoubleUnaryOperator weightFun) {
        var testRecord = new DataRecord<TestRawNamespace, RawValue>(TestRawNamespace.class);
        testRecord.put(TestRawNamespace.target, RawValue.singleNumerical(targetVariable));
        VwInstanceEncoder<TestRawNamespace> subject;
        if (weightFun == null) {
            subject = new VwInstanceEncoder<>(
                    testRawNamespaceRawValueDataRecord -> featureVector,
                    binary);
        } else {
            subject = new VwInstanceEncoder<>(
                    testRawNamespaceRawValueDataRecord -> featureVector,
                    binary,
                    weightFun
            );
        }
        var encoded = subject.apply(testRecord);
        assertEquals(expected, encoded);
    }
}