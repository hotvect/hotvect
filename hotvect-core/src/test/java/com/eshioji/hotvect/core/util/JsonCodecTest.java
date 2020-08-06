package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.core.TestRawNamespace;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
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
        var decoded = decoder.apply(testInputWithAllValueTypes());
        var reEncoded = encoder.apply(decoded);
        assertJsonEquals(testInputWithAllValueTypes(), reEncoded);
    }

    @Test
    void valuesCanBeMissing() {
        var allKeys = EnumSet.allOf(TestRawNamespace.class);

        allKeys.stream().map(k -> {
            var record = decoder.apply(testInputWithAllValueTypes()).asEnumMap();
            record.remove(k);
            return new AbstractMap.SimpleImmutableEntry<>(k, record);
        }).forEach(e -> {
            assertNull(e.getValue().get(e.getKey()));
            var encodedInput = encoder.apply(new DataRecord<>(TestRawNamespace.class, e.getValue()));
            assertJsonEquals(encodedInput, decoder.andThen(encoder).apply(encodedInput));
        });
    }

    @Test
    void valuesCanBeEmpty() {
        var decoded = decoder.apply(testInputWithAllValuesEmpty());
        var reEncoded = encoder.apply(decoded);
        assertJsonEquals("{}", reEncoded);
    }
}
