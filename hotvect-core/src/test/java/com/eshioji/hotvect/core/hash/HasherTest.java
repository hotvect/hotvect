package com.eshioji.hotvect.core.hash;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.TestFeatureNamespace;
import com.eshioji.hotvect.core.TestRawNamespace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.eshioji.hotvect.core.TestRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class HasherTest {

    @Test
    void hashRawValuesAndPreserveProcessedValues() {
        DataRecord<TestRawNamespace, RawValue> testRecord = getTestRecord();
        Hasher<TestFeatureNamespace> subject = new Hasher<>(TestFeatureNamespace.class);
        DataRecord<TestFeatureNamespace, HashedValue> hashed = subject.apply(mapped(testRecord));

        for (Map.Entry<TestRawNamespace, RawValue> e : testRecord.asEnumMap().entrySet()) {
            HashedValue actual = hashed.get(mapped(e.getKey()));
            switch (e.getKey().getValueType()){
                case SINGLE_STRING:  {
                    assertEquals(1118836419, actual.getSingleCategorical());
                    break;
                }
                case STRINGS: {
                    assertArrayEquals(new int[]{1729973133,-667790,-1526670773}, actual.getCategoricals());
                    break;
                }
                case SINGLE_NUMERICAL: {
                    assertEquals(789.0, actual.getSingleNumerical());
                    break;
                }
                case STRINGS_TO_NUMERICALS: {
                    assertArrayEquals(new int[]{208855138,1505568724,-674937657}, actual.getCategoricals());
                    assertArrayEquals(new double[]{16,17,18}, actual.getNumericals());
                    break;
                }
                case SINGLE_CATEGORICAL:
                case CATEGORICALS:
                case CATEGORICALS_TO_NUMERICALS: assertEquals(
                        testRecord.get(e.getKey()).getCategoricalsToNumericals(),
                        actual.getCategoricalsToNumericals()
                );
                break;
                default: throw new AssertionError();
            }

        }
    }

    @Test
    void emptyInput() {
        DataRecord<TestRawNamespace, RawValue> testRecord = getEmptyTestRecord();
        Hasher<TestFeatureNamespace> subject = new Hasher<>(TestFeatureNamespace.class);
        DataRecord<TestFeatureNamespace, HashedValue> hashed = subject.apply(mapped(testRecord));
        assertTrue(hashed.asEnumMap().isEmpty());
    }



}