package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.TestRawNamespace;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.EnumMap;
import java.util.EnumSet;

import static com.eshioji.hotvect.core.TestRecords.testInputWithAllValueTypes;
import static com.eshioji.hotvect.core.TestRecords.testInputWithAllValuesEmpty;
import static com.eshioji.hotvect.testutils.TestUtils.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JsonCodecTest {
    private final JsonRecordDecoder<TestRawNamespace> decoder = new JsonRecordDecoder<>(TestRawNamespace.class);
    private final JsonRecordEncoder<TestRawNamespace> encoder = new JsonRecordEncoder<>();

    @Test
    void allValueTypesCanBeRead() {
        DataRecord<TestRawNamespace, RawValue> decoded = decoder.apply(testInputWithAllValueTypes());
        String reEncoded = encoder.apply(decoded);
        assertJsonEquals(testInputWithAllValueTypes(), reEncoded);
    }

    @Test
    void valuesCanBeMissing() {
        EnumSet<TestRawNamespace> allKeys = EnumSet.allOf(TestRawNamespace.class);

        allKeys.stream().map(k -> {
            EnumMap<TestRawNamespace, RawValue> record = decoder.apply(testInputWithAllValueTypes()).asEnumMap();
            record.remove(k);
            return new AbstractMap.SimpleImmutableEntry<>(k, record);
        }).forEach(e -> {
            assertNull(e.getValue().get(e.getKey()));
            String encodedInput = encoder.apply(new DataRecord<>(TestRawNamespace.class, e.getValue()));
            assertJsonEquals(encodedInput, decoder.andThen(encoder).apply(encodedInput));
        });
    }

    @Test
    void valuesCanBeEmpty() {
        DataRecord<TestRawNamespace, RawValue> decoded = decoder.apply(testInputWithAllValuesEmpty());
        String reEncoded = encoder.apply(decoded);
        assertJsonEquals("{}", reEncoded);
    }
}
