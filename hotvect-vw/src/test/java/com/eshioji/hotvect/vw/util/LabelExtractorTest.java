package com.eshioji.hotvect.vw.util;


import com.eshioji.hotvect.vw.LabelExtractor;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LabelExtractorTest {

    @Test
    void withValue() {
        var dataRecord = TestRecords.getTestRecord();
        var subject = new LabelExtractor<TestRawNamespace>();
        assertEquals(1, subject.applyAsDouble(dataRecord));
    }

    @Test
    void withOutValue() {
        var dataRecord = TestRecords.getEmptyTestRecord();
        var subject = new LabelExtractor<TestRawNamespace>();
        assertThrows(NoSuchElementException.class, () -> subject.applyAsDouble(dataRecord));
    }

}