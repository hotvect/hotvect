package com.hotvect.vw.util;


import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.raw.Example;
import com.hotvect.api.data.raw.RawValue;
import com.hotvect.vw.LabelExtractor;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LabelExtractorTest {

    @Test
    void withValue() {
        Example<DataRecord<TestRawNamespace, RawValue>> dataRecord = TestRecords.getTestRecord();
        DataRecord<TestRawNamespace, RawValue> testInput = dataRecord.getRecord();
        testInput.put(TestRawNamespace.target, RawValue.singleNumerical(1.0));
        LabelExtractor<TestRawNamespace> subject = new LabelExtractor<TestRawNamespace>();
        assertEquals(1, subject.applyAsDouble(dataRecord.getRecord()));
    }

    @Test
    void withOutValue() {
        DataRecord<TestRawNamespace, RawValue> dataRecord = TestRecords.getEmptyTestRecord();
        LabelExtractor<TestRawNamespace> subject = new LabelExtractor<TestRawNamespace>();
        assertThrows(NoSuchElementException.class, () -> subject.applyAsDouble(dataRecord));
    }

}