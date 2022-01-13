package com.hotvect.api.data;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An {@link EnumMap} like class used to store examples.
 * It has less features than {@link EnumMap} and performs better under extreme use.
 */
public class DataRecord<K extends Enum<K>, V> {
    private final Object[] values;
    private final Class<K> keyClass;

    public DataRecord(Class<K> keyClazz) {
        this.values = new Object[keyClazz.getEnumConstants().length];
        this.keyClass = keyClazz;
    }

    public DataRecord(Class<K> keyClazz, EnumMap<K, V> values) {
        this(keyClazz);
        for (K k : keyClazz.getEnumConstants()) {
            this.values[k.ordinal()] = values.get(k);
        }
    }

    public V get(K k) {
        @SuppressWarnings("unchecked")
        V ret = (V) values[k.ordinal()];
        return ret;
    }

    public void put(K k, V v) {
        values[k.ordinal()] = v;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataRecord)) return false;
        DataRecord<?, ?> dataRecord = (DataRecord<?, ?>) o;
        return Arrays.equals(values, dataRecord.values) &&
                Objects.equals(keyClass, dataRecord.keyClass);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(keyClass);
        result = 31 * result + Arrays.hashCode(values);
        return result;
    }

    /**
     * @return a shallow copy of this {@link DataRecord} as {@link EnumMap}
     */
    public EnumMap<K, V> asEnumMap() {
        EnumMap<K, V> ret = new EnumMap<>(keyClass);
        for (K k : keyClass.getEnumConstants()) {
            @SuppressWarnings("unchecked")
            V v = (V) values[k.ordinal()];
            if (v != null) {
                ret.put(k, v);
            }
        }
        return ret;
    }

    @Override
    public String toString() {
        String content = Arrays.stream(keyClass.getEnumConstants())
                .map(k -> String.format("%s:%s", k, values[k.ordinal()]))
                .collect(Collectors.joining(", "));

        return "DataRecord{" +
                "values=" + content +
                '}';
    }

}
