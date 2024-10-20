package com.hotvect.api.data.common;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Namespace;

import java.util.Map;

public interface NamespacedRecord<K, V>{

    V get(Object key);

    void put(K key, V value);

    NamespacedRecord<K,V> shallowCopy();

    void merge(NamespacedRecord<? extends Namespace, V> nonMemoized);

    Map<K, V> asMap();

    int size();

    static <K, V> NamespacedRecord<K, V> empty() {

        return new NamespacedRecord<>() {
            @Override
            public V get(Object key) {
                return null;
            }

            @Override
            public void put(K key, V value) {
                throw new UnsupportedOperationException("This namespaced record is immutable");
            }

            @Override
            public NamespacedRecord<K, V> shallowCopy() {
                // This method is used to modify the resulting copy, so return a mutable one
                return new NamespacedRecordImpl<>();
            }

            @Override
            public void merge(NamespacedRecord<? extends Namespace, V> nonMemoized) {
                throw new UnsupportedOperationException("This namespaced record is immutable");
            }

            @Override
            public Map<K, V> asMap() {
                return ImmutableMap.of();
            }

            @Override
            public int size() {
                return 0;
            }
        };
    }
}
