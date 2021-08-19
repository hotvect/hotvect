package com.eshioji.hotvect.vw.util;


import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.vw.LabelExtractor;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LabelExtractorTest {

    @Test
    void withValue() {
        var dataRecord = TestRecords.getTestRecord();
        var testInput = dataRecord.getRecord();
        testInput.put(TestRawNamespace.target, RawValue.singleNumerical(1.0));
        var subject = new LabelExtractor<TestRawNamespace>();
        assertEquals(1, subject.applyAsDouble(dataRecord.getRecord()));
    }

    @Test
    void withOutValue() {
        var dataRecord = TestRecords.getEmptyTestRecord();
        var subject = new LabelExtractor<TestRawNamespace>();
        assertThrows(NoSuchElementException.class, () -> subject.applyAsDouble(dataRecord));
    }

}