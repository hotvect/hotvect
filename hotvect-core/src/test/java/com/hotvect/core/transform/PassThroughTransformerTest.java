package com.hotvect.core.transform;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.hashed.HashedValueType;
import com.hotvect.api.data.raw.RawValue;
import com.hotvect.core.TestFeatureNamespace;
import com.hotvect.core.TestRawNamespace;
import com.hotvect.core.util.AutoMapper;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static com.hotvect.core.TestRecords.getTestRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassThroughTransformerTest {

    @Test
    void apply() {
        EnumMap<TestFeatureNamespace, Transformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<TestFeatureNamespace, Transformation<DataRecord<TestRawNamespace, RawValue>>>(TestFeatureNamespace.class);
        transformations.put(TestFeatureNamespace.parsed_1, r -> {
            int x = r.get(TestRawNamespace.single_categorical_1).getSingleCategorical();
            return RawValue.singleCategorical(x * 10);
        });

        DataRecord<TestRawNamespace, RawValue> testRecord = getTestRecord();

        PassThroughTransformer<TestRawNamespace, TestFeatureNamespace> subject = new PassThroughTransformer<>(TestRawNamespace.class, TestFeatureNamespace.class, transformations);


        DataRecord<TestFeatureNamespace, RawValue> actual = subject.apply(testRecord);

        DataRecord<TestFeatureNamespace, RawValue> expected = new AutoMapper<TestRawNamespace, TestFeatureNamespace, RawValue>(TestRawNamespace.class, TestFeatureNamespace.class).apply(testRecord);
        expected.put(TestFeatureNamespace.parsed_1, RawValue.singleCategorical(testRecord.get(TestRawNamespace.single_categorical_1).getSingleCategorical() * 10));

        assertEquals(expected, actual);
    }


    @Test
    public void trytoTransformAutomappedfield() {
        EnumMap<TestFeatureNamespace, Transformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<TestFeatureNamespace, Transformation<DataRecord<TestRawNamespace, RawValue>>>(TestFeatureNamespace.class);
        transformations.put(TestFeatureNamespace.single_categorical_1, r -> {
            throw new AssertionError();
        });

        assertThrows(IllegalArgumentException.class,
                () -> new PassThroughTransformer<>(TestRawNamespace.class, TestFeatureNamespace.class, transformations));

    }

    enum WrongType1 implements FeatureNamespace {
        single_categorical_1;

        @Override
        public HashedValueType getValueType() {
            return HashedValueType.NUMERICAL;
        }
    }

    enum WrongType2 implements FeatureNamespace {
        single_numerical_1;

        @Override
        public HashedValueType getValueType() {
            return HashedValueType.CATEGORICAL;
        }
    }

    @Test
    public void mapCategoricalIntoNumerical() {
        EnumMap<WrongType1, Transformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<WrongType1, Transformation<DataRecord<TestRawNamespace, RawValue>>>(WrongType1.class);
        assertThrows(IllegalArgumentException.class, () -> new PassThroughTransformer<>(TestRawNamespace.class, WrongType1.class, transformations));

    }

    @Test
    public void mapNumericalIntoCategorical() {
        EnumMap<WrongType2, Transformation<DataRecord<TestRawNamespace, RawValue>>> transformations = new EnumMap<WrongType2, Transformation<DataRecord<TestRawNamespace, RawValue>>>(WrongType2.class);
        assertThrows(IllegalArgumentException.class, () -> new PassThroughTransformer<>(TestRawNamespace.class, WrongType2.class, transformations));
    }

}