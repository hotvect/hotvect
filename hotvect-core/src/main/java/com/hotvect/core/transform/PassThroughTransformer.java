package com.hotvect.core.transform;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.raw.RawValue;
import com.hotvect.core.util.AutoMapper;
import com.google.common.collect.Sets;

import java.lang.reflect.Array;
import java.util.EnumMap;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toSet;


/**
 * Transforms the input into output using the functions supplied in the constructor. If both input and output has keys
 * with the same name, the value is "passed through" (copied over).
 *
 * @param <IN>
 * @param <OUT>
 */
@Deprecated
public class PassThroughTransformer<IN extends Enum<IN> & Namespace, OUT extends Enum<OUT> & Namespace>
        implements Transformer<DataRecord<IN, RawValue>, OUT> {
    private final AutoMapper<IN, OUT, RawValue> autoMapper;
    private final DataRecord<OUT, Transformation<DataRecord<IN, RawValue>>> transformations;
    private final OUT[] transformKeys;

    public PassThroughTransformer(Class<IN> inKey, Class<OUT> outKey) {
        this(inKey, outKey, new EnumMap<>(outKey));
    }


        /**
         * Transforms the input into output using the {@code transformations} supplied in the constructor.
         * The value for a given key in the output is the calculation result of the function that was registered for that
         * key. If no function was registered for a key in the output, but a key with the same name exists in the input,
         * then the identity function is used (the value is simply copied over from input to output).
         *
         * @param inKey key class to pass value from
         * @param outKey key class to pass value to
         * @param transformations definition of transformation to perform on the values
         */
    @SuppressWarnings("unchecked")
    public PassThroughTransformer(Class<IN> inKey, Class<OUT> outKey, EnumMap<OUT, Transformation<DataRecord<IN, RawValue>>> transformations) {
        this.autoMapper = new AutoMapper<>(inKey, outKey);
        this.transformations = new DataRecord<>(outKey);
        transformations.forEach(this.transformations::put);

        // Validations
        Set<String> automapped = autoMapper.mapped().keySet().stream().map(Enum::name).collect(toSet());
        Set<String> toTransform = this.transformations.asEnumMap().keySet().stream().map(Enum::name).collect(toSet());
        Set<String> mappedAndTransformed = Sets.intersection(automapped, toTransform);

        checkArgument(mappedAndTransformed.size() == 0,
                "transformed feature's namespace cannot share the same name with any of the input's namespaces. " +
                        " Offending namespaces" + mappedAndTransformed);

        autoMapper.mapped().forEach((k, v) -> checkArgument(k.getValueType().hasNumericValues() == v.getValueType().hasNumericValues(),
                "You are trying to map a raw value into a hashed value with incompatible value type." +
                        (k.getValueType().hasNumericValues() ?
                                k + " has numeric values and hence must be mapped to a numeric hashed value type." :
                                k + " does not have numeric values and hence must be mapped to a categorical hashed value type.")
                ));


        @SuppressWarnings("unchecked")
        OUT[] transformKeys = (OUT[]) Array.newInstance(outKey, this.transformations.asEnumMap().size());
        this.transformKeys = this.transformations.asEnumMap().keySet().toArray(transformKeys);
    }

    /**
     * Transform the specified record
     * @param toTransform the record to transform
     * @return the transformed record
     */
    @Override
    public DataRecord<OUT, RawValue> apply(DataRecord<IN, RawValue> toTransform) {
        DataRecord<OUT, RawValue> mapped = autoMapper.apply(toTransform);
        for (OUT p : transformKeys) {
            Transformation<DataRecord<IN, RawValue>> transformation = transformations.get(p);
            RawValue parsed = transformation.apply(toTransform);
            if (parsed != null) {
                mapped.put(p, parsed);
            }
        }
        return mapped;
    }

}
