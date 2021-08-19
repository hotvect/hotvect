package com.eshioji.hotvect.core.transform;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.hashed.HashedNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValueType;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.TestHashedNamespace;
import com.eshioji.hotvect.core.TestRawNamespace;
import com.eshioji.hotvect.core.util.AutoMapper;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static com.eshioji.hotvect.core.TestRecords.getTestRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassThroughTransformerTest {

    @Test
    void apply() {
        var transformations = new EnumMap<TestHashedNamespace, Transformation<DataRecord<TestRawNamespace, RawValue>>>(TestHashedNamespace.class);
        transformations.put(TestHashedNamespace.parsed_1, r -> {
            var x = r.get(TestRawNamespace.single_categorical_1).getSingleCategorical();
            return RawValue.singleCategorical(x * 10);
        });

        var testRecord = getTestRecord();

        var subject = new PassThroughTransformer<>(TestRawNamespace.class, TestHashedNamespace.class, transformations);


        var actual = subject.apply(testRecord);

        var expected = new AutoMapper<TestRawNamespace, TestHashedNamespace, RawValue>(TestRawNamespace.class, TestHashedNamespace.class).apply(testRecord);
        expected.put(TestHashedNamespace.parsed_1, RawValue.singleCategorical(testRecord.get(TestRawNamespace.single_categorical_1).getSingleCategorical() * 10));

        assertEquals(expected, actual);
    }


    @Test
    public void trytoTransformAutomappedfield() {
        var transformations = new EnumMap<TestHashedNamespace, Transformation<DataRecord<TestRawNamespace, RawValue>>>(TestHashedNamespace.class);
        transformations.put(TestHashedNamespace.single_categorical_1, r -> {
            throw new AssertionError();
        });

        assertThrows(IllegalArgumentException.class,
                () -> new PassThroughTransformer<>(TestRawNamespace.class, TestHashedNamespace.class, transformations));

    }

    enum WrongType1 implements HashedNamespace {
        single_categorical_1;

        @Override
        public HashedValueType getValueType() {
            return HashedValueType.NUMERICAL;
        }
    }

    enum WrongType2 implements HashedNamespace {
        single_numerical_1;

        @Override
        public HashedValueType getValueType() {
            return HashedValueType.CATEGORICAL;
        }
    }

    @Test
    public void mapCategoricalIntoNumerical() {
        var transformations = new EnumMap<WrongType1, Transformation<DataRecord<TestRawNamespace, RawValue>>>(WrongType1.class);
        assertThrows(IllegalArgumentException.class, () -> new PassThroughTransformer<>(TestRawNamespace.class, WrongType1.class, transformations));

    }

    @Test
    public void mapNumericalIntoCategorical() {
        var transformations = new EnumMap<WrongType2, Transformation<DataRecord<TestRawNamespace, RawValue>>>(WrongType2.class);
        assertThrows(IllegalArgumentException.class, () -> new PassThroughTransformer<>(TestRawNamespace.class, WrongType2.class, transformations));
    }

}