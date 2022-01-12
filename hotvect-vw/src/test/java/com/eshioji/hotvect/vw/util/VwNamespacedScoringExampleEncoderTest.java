package com.eshioji.hotvect.vw.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.transform.regression.PassThroughRegressionTransformer;
import com.eshioji.hotvect.core.transform.regression.ScoringTransformer;
import com.eshioji.hotvect.vw.VwNamespacedScoringExampleEncoder;
import org.junit.jupiter.api.Test;

import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VwNamespacedScoringExampleEncoderTest {
    private static final ScoringTransformer<DataRecord<TestRawNamespace, RawValue>, TestFeatureNamespace> SCORING_TRANSFORMER =
            new PassThroughRegressionTransformer<>(TestRawNamespace.class, TestFeatureNamespace.class);


    @Test
    void nonBinaryWithoutWeights() {
        double targetVariable = 99.1;
        ScoringExample<DataRecord<TestRawNamespace, RawValue>, Double> testRecord = TestRecords.getTestRecord();
        testRecord = new ScoringExample<>(testRecord.getRecord(), targetVariable);
        String expected = "99.1 |a 123:1  |b 4:1 5:1 6:1  |c 0:789  |d 10:10 11:11 12:12  |e 1118836419:1  |f 1729973133:1 -667790:1 -1526670773:1  |g 208855138:16 1505568724:17 -674937657:18 ";
        testEncoding(testRecord, expected, false);
    }

//    @Test
//    void binaryWithoutWeights() {
//        var targetVariable = 99.1;
//        var featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
//        var expected = "1 | 1:1 2:2 3:3.3456 ";
//        testEncoding(targetVariable, featureVector, expected, true);
//
//        targetVariable = 0;
//        expected = "-1 | 1:1 2:2 3:3.3456 ";
//        testEncoding(targetVariable, featureVector, expected, true);
//
//    }
//
//    @Test
//    void withWeights() {
//        var targetVariable = 99.1;
//        var featureVector = new SparseVector(new int[]{1, 2, 3}, new double[]{1.0, 2.0, 3.3456});
//        var expected = "1 198.2 | 1:1 2:2 3:3.3456 ";
//        testEncoding(targetVariable, featureVector, expected, true, d -> d * 2);
//    }

    private void testEncoding(ScoringExample<DataRecord<TestRawNamespace, RawValue>, Double> testRecord, String expected, boolean binary) {
        testEncoding(testRecord, expected, binary, null);
    }


    private void testEncoding(ScoringExample<DataRecord<TestRawNamespace, RawValue>, Double> testRecord, String expected, boolean binary, ToDoubleFunction<Double> weightFun) {
        VwNamespacedScoringExampleEncoder<DataRecord<TestRawNamespace, RawValue>, TestFeatureNamespace, Double> subject;
        subject = new VwNamespacedScoringExampleEncoder<>(SCORING_TRANSFORMER, d -> d, TestFeatureNamespace.class, binary, weightFun);
        String encoded = subject.apply(testRecord);
        assertEquals(expected, encoded);
    }}