package com.hotvect.api.data.common;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Mapping<K, V>{
    private final K[] keys;
    private final V[] values;

    public Mapping(K[] keys, V[] values) {
        if(keys.length != values.length){
            throw new IllegalArgumentException("Keys and values must have the same length. " + Arrays.toString(keys) + ":" + Arrays.toString(values));
        }
        this.keys = keys;
        this.values = values;
    }

    public Mapping(Map<?, ?> source, Class<K> kClass, Class<V> vClass){

        this.keys = (K[]) Array.newInstance(kClass, source.size());
        this.values = (V[]) Array.newInstance(vClass, source.size());
        int i = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            K k = (K) entry.getKey();
            V v = (V) entry.getValue();
            this.keys[i] = k;
            this.values[i] = v;
            i++;
        }
    }


    public K[] keys() {
        return this.keys;
    }

    public V[] values() {
        return this.values;
    }

    public Map<K, V> asMap() {
        Map<K, V> map = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
    }

    public static final <K, V> Mapping<K, V> empty(Class<K> kClass, Class<V> vClass) {
        K[] keys = (K[]) Array.newInstance(kClass, 0);
        V[] values = (V[]) Array.newInstance(vClass, 0);

        return new Mapping<>(keys, values);
    }
}
