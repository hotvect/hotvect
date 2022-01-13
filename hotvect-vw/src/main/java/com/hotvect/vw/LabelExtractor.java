package com.hotvect.vw;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.raw.RawNamespace;
import com.hotvect.api.data.raw.RawValue;

import java.util.Map;
import java.util.function.ToDoubleFunction;

import static com.google.common.base.Preconditions.checkNotNull;

public class LabelExtractor<R extends Enum<R> & RawNamespace> implements ToDoubleFunction<DataRecord<R, RawValue>> {
    private final String fieldName;

    public LabelExtractor() {
        this("target");
    }

    public LabelExtractor(String fieldName) {
        this.fieldName = fieldName;
    }

    @Override
    public double applyAsDouble(DataRecord<R, RawValue> record) {
        checkNotNull(fieldName);
        return record.asEnumMap().entrySet().stream().filter(p ->
                fieldName.equals(p.getKey().toString())
        ).map(Map.Entry::getValue).findFirst().get().getSingleNumerical();
    }

}
