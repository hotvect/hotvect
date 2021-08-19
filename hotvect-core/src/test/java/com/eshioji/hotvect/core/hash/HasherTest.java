package com.eshioji.hotvect.core.hash;

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
        var testRecord = getTestRecord();
        var subject = new Hasher<>(TestFeatureNamespace.class);
        var hashed = subject.apply(mapped(testRecord));

        for (Map.Entry<TestRawNamespace, RawValue> e : testRecord.asEnumMap().entrySet()) {
            var actual = hashed.get(mapped(e.getKey()));
            switch (e.getKey().getValueType()){
                case SINGLE_STRING -> assertEquals(1118836419, actual.getSingleCategorical());
                case STRINGS -> assertArrayEquals(new int[]{1729973133,-667790,-1526670773}, actual.getCategoricals());
                case SINGLE_NUMERICAL -> assertEquals(789.0, actual.getSingleNumerical());
                case STRINGS_TO_NUMERICALS -> {
                    assertArrayEquals(new int[]{208855138,1505568724,-674937657}, actual.getCategoricals());
                    assertArrayEquals(new double[]{16,17,18}, actual.getNumericals());
                }
                case SINGLE_CATEGORICAL, CATEGORICALS, CATEGORICALS_TO_NUMERICALS -> assertEquals(
                        testRecord.get(e.getKey()).getCategoricalsToNumericals(),
                        actual.getCategoricalsToNumericals()
                );
            }

        }
    }

    @Test
    void emptyInput() {
        var testRecord = getEmptyTestRecord();
        var subject = new Hasher<>(TestFeatureNamespace.class);
        var hashed = subject.apply(mapped(testRecord));
        assertTrue(hashed.asEnumMap().isEmpty());
    }



}