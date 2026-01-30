//package com.hotvect.core.transform;
//
//import com.hotvect.api.data.*;
//import com.hotvect.core.TestFeatureNamespace;
//import com.hotvect.core.TestRawNamespace;
//import com.hotvect.core.TestRecords;
//import com.hotvect.core.transform.regression.PassThroughRegressionTransformer;
//import com.hotvect.core.transform.regression.RecordTransformation;
//import com.hotvect.core.util.AutoMapper;
//import org.junit.jupiter.api.Test;
//
//import java.util.EnumMap;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//
//class PassThroughRankingScoringTransformerTest {
//
//    @Test
//    void apply() {
//        EnumMap<TestFeatureNamespace, RecordTransformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<>(TestFeatureNamespace.class);
//        transformations.put(TestFeatureNamespace.parsed_1, r -> {
//            int x = r.get(TestRawNamespace.single_categorical_1).getSingleCategorical();
//            return RawValue.singleCategorical(x * 10);
//        });
//
//        DataRecord<TestRawNamespace, RawValue> testRecord = TestRecords.getTestRecord();
//
//        PassThroughRegressionTransformer<TestRawNamespace, TestFeatureNamespace> subject = new PassThroughRegressionTransformer<>(TestRawNamespace.class, TestFeatureNamespace.class, transformations);
//
//
//        DataRecord<TestFeatureNamespace, RawValue> actual = subject.apply(testRecord);
//
//        DataRecord<TestFeatureNamespace, RawValue> expected = new AutoMapper<TestRawNamespace, TestFeatureNamespace, RawValue>(TestRawNamespace.class, TestFeatureNamespace.class).apply(testRecord);
//        expected.put(TestFeatureNamespace.parsed_1, RawValue.singleCategorical(testRecord.get(TestRawNamespace.single_categorical_1).getSingleCategorical() * 10));
//
//        assertEquals(expected, actual);
//    }
//
//
//    @Test
//    public void trytoTransformAutomappedfield() {
//        EnumMap<TestFeatureNamespace, RecordTransformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<>(TestFeatureNamespace.class);
//        transformations.put(TestFeatureNamespace.single_categorical_1, r -> {
//            throw new AssertionError();
//        });
//
//        assertThrows(IllegalArgumentException.class,
//                () -> new PassThroughRegressionTransformer<>(TestRawNamespace.class, TestFeatureNamespace.class, transformations));
//
//    }
//
//    enum WrongType1 implements FeatureNamespace {
//        single_categorical_1;
//
//        @Override
//        public HashedValueType getValueType() {
//            return HashedValueType.NUMERICAL;
//        }
//
//        @Override
//        public ValueType getFeatureValueType() {
//            return HashedValueType.NUMERICAL;
//        }
//    }
//
//    enum WrongType2 implements FeatureNamespace {
//        single_numerical_1;
//
//        @Override
//        public HashedValueType getValueType() {
//            return HashedValueType.CATEGORICAL;
//        }
//
//        @Override
//        public ValueType getFeatureValueType() {
//            return HashedValueType.CATEGORICAL;
//        }
//    }
//
//    @Test
//    public void mapCategoricalIntoNumerical() {
//        EnumMap<WrongType1, RecordTransformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<>(WrongType1.class);
//        assertThrows(IllegalArgumentException.class, () -> new PassThroughRegressionTransformer<>(TestRawNamespace.class, WrongType1.class, transformations));
//
//    }
//
//    @Test
//    public void mapNumericalIntoCategorical() {
//        EnumMap<WrongType2, RecordTransformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<>(WrongType2.class);
//        assertThrows(IllegalArgumentException.class, () -> new PassThroughRegressionTransformer<>(TestRawNamespace.class, WrongType2.class, transformations));
//    }
//
//}