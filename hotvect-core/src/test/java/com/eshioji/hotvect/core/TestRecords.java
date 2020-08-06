package com.eshioji.hotvect.core;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.util.AutoMapper;
import com.eshioji.hotvect.core.util.JsonCodecTest;
import com.eshioji.hotvect.core.util.JsonRecordDecoder;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;

import java.io.IOException;

public enum TestRecords {
    ;
    public static DataRecord<TestRawNamespace, RawValue> getEmptyTestRecord() {
        return new JsonRecordDecoder<>(TestRawNamespace.class).apply(testInputWithAllValuesEmpty());
    }


    public static DataRecord<TestRawNamespace, RawValue> getTestRecord() {
        return new JsonRecordDecoder<>(TestRawNamespace.class).apply(testInputWithAllValueTypes());
    }

    public static DataRecord<TestHashedNamespace, RawValue> mapped(DataRecord<TestRawNamespace, RawValue> toMap){
        AutoMapper<TestRawNamespace, TestHashedNamespace, RawValue> automapper = new AutoMapper<>(TestRawNamespace.class, TestHashedNamespace.class);
        return automapper.apply(toMap);
    }

    public static TestHashedNamespace mapped(TestRawNamespace rns){
        return TestHashedNamespace.valueOf(rns.name());
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
