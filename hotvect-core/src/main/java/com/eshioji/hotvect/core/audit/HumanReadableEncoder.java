package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.core.transform.Transformer;
import com.eshioji.hotvect.core.util.DataRecords;
import com.eshioji.hotvect.core.util.JsonRecordEncoder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static com.eshioji.hotvect.core.util.DataRecords.pojonize;

public class HumanReadableEncoder<R> implements ExampleEncoder<R> {
    private static final ObjectMapper OM = new ObjectMapper();
    private final Transformer<R, ?> transformer;

    public HumanReadableEncoder(Transformer<R, ?> transformer) {
        this.transformer = transformer;
    }

    @Override
    public String apply(Example<R> toEncode) {
        var target = toEncode.getTarget();
        var transformed = this.transformer.apply(toEncode.getRecord());

        Map<String, Object> pojonized = DataRecords.pojonize(transformed);
        pojonized.put("target", target);

        try {
            return OM.writeValueAsString(pojonized);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }
}
