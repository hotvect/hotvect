package com.hotvect.api.data.common;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Map;
import java.util.function.IntFunction;

public class Mapping<K, V> {
    private final K[] keys;
    private final V[] values;

    public Mapping(K[] keys, V[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Keys and values must have the same length. " + Arrays.toString(keys) + ":" + Arrays.toString(values));
        }
        this.keys = keys;
        this.values = values;
    }
    public Mapping(Map<?, ?> source, IntFunction<K[]> keyArrayGen, IntFunction<V[]> valueArrayGen) {
        this.keys = keyArrayGen.apply(source.size());
        this.values = valueArrayGen.apply(source.size());
        int i = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            K k = (K) entry.getKey();
            V v = (V) entry.getValue();
            this.keys[i] = k;
            this.values[i] = v;
            i++;
        }
    }

    @Deprecated(forRemoval = true)
    public Mapping(Map<?, ?> source, Class<K> kClass, Class<V> vClass) {
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

    @Deprecated(forRemoval = true)
    public static <K, V> Mapping<K, V> empty(Class<K> kClass, Class<V> vClass) {
        K[] keys = (K[]) Array.newInstance(kClass, 0);
        V[] values = (V[]) Array.newInstance(vClass, 0);
        return new Mapping<>(keys, values);
    }

    public static <K, V> Mapping<K, V> empty(IntFunction<K[]> keyArrayGen, IntFunction<V[]> valueArrayGen) {
        K[] keys = keyArrayGen.apply(0);
        V[] values = valueArrayGen.apply(0);
        return new Mapping<>(keys, values);
    }

    public Map<K, V> asMap(){
        return new Object2ObjectOpenHashMap<>(this.keys, this.values);
    }
}