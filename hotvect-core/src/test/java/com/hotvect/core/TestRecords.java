package com.hotvect.core;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.RawValue;
import com.hotvect.core.util.AutoMapper;
import com.hotvect.core.util.JsonCodecTest;
import com.hotvect.core.util.JsonRecordDecoder;

import java.io.IOException;

public enum TestRecords {
    ;
    public static DataRecord<TestRawNamespace, RawValue> getEmptyTestRecord() {
        return new JsonRecordDecoder<>(TestRawNamespace.class).apply(testInputWithAllValuesEmpty());
    }


    public static DataRecord<TestRawNamespace, RawValue> getTestRecord() {
        return new JsonRecordDecoder<>(TestRawNamespace.class).apply(testInputWithAllValueTypes());
    }

    public static DataRecord<TestFeatureNamespace, RawValue> mapped(DataRecord<TestRawNamespace, RawValue> toMap){
        AutoMapper<TestRawNamespace, TestFeatureNamespace, RawValue> automapper = new AutoMapper<>(TestRawNamespace.class, TestFeatureNamespace.class);
        return automapper.apply(toMap);
    }

    public static TestFeatureNamespace mapped(TestRawNamespace rns){
        return TestFeatureNamespace.valueOf(rns.name());
    }
    public static String testInputWithAllValueTypes() {
        try {
            return Resources.toString(JsonCodecTest.class.getResource("all_value_types.json"), Charsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String testInputWithAllValuesEmpty() {
        try {
            return Resources.toString(JsonCodecTest.class.getResource("all_value_types_empty.json"), Charsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
