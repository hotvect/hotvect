package com.eshioji.hotvect.vw.util;


import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.vw.LabelExtractor;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LabelExtractorTest {

    @Test
    void withValue() {
        ScoringExample<DataRecord<TestRawNamespace, RawValue>> dataRecord = TestRecords.getTestRecord();
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