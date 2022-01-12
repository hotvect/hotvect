package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.RawNamespace;
import com.eshioji.hotvect.api.data.RawValue;

import java.util.Map;
import java.util.function.ToDoubleFunction;

import static com.google.common.base.Preconditions.checkNotNull;

@Deprecated
public class LabelExtractor<RECORD extends Enum<RECORD> & RawNamespace> implements ToDoubleFunction<DataRecord<RECORD, RawValue>> {
    private final String fieldName;

    public LabelExtractor() {
        this("target");
    }

    public LabelExtractor(String fieldName) {
        this.fieldName = fieldName;
    }

    @Override
    public double applyAsDouble(DataRecord<RECORD, RawValue> record) {
        checkNotNull(fieldName);
        return record.asEnumMap().entrySet().stream().filter(p ->
                fieldName.equals(p.getKey().toString())
        ).map(Map.Entry::getValue).findFirst().get().getSingleNumerical();
    }

}
