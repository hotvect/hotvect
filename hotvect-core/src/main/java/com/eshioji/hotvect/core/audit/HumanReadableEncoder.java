package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.codec.regression.ExampleEncoder;
import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.raw.regression.Example;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.transform.Transformer;
import com.eshioji.hotvect.core.util.DataRecords;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class HumanReadableEncoder<R> implements ExampleEncoder<R> {
    private static final ObjectMapper OM = new ObjectMapper();
    private final Transformer<R, ?> transformer;

    public HumanReadableEncoder(Transformer<R, ?> transformer) {
        this.transformer = transformer;
    }

    @Override
    public String apply(Example<R> toEncode) {
        double target = toEncode.getTarget();
        DataRecord<?, RawValue> transformed = this.transformer.apply(toEncode.getRecord());

        Map<String, Object> pojonized = DataRecords.pojonize(transformed);
        pojonized.put("target", target);

        try {
            return OM.writeValueAsString(pojonized);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }
}
