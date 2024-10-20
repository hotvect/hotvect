package com.hotvect.api.data.common;



import com.hotvect.api.data.Namespace;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

public class NamespacedRecordImpl<K, V> implements NamespacedRecord<K, V> {
    private final IdentityHashMap<K, V> delegate;
    public NamespacedRecordImpl(K[] keys, V[] vals) {
        this.delegate = new IdentityHashMap<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            this.put(keys[i], vals[i]);
        }
    }

    public NamespacedRecordImpl(Map<K,V> kvMap) {
        this.delegate = new IdentityHashMap<>(kvMap);
    }

    private NamespacedRecordImpl(NamespacedRecordImpl<K, V> kvNamespacedRecordImpl) {
        this.delegate = (IdentityHashMap<K, V>) kvNamespacedRecordImpl.delegate.clone();
    }

    public NamespacedRecordImpl() {
        delegate = new IdentityHashMap<>(70);
    }

    @Override
    public V get(Object key) {
        return delegate.get(key);
    }

    @Override
    public void put(K key, V value) {
        this.delegate.put(key, value);
    }

    @Override
    public NamespacedRecord<K, V> shallowCopy() {
        return new NamespacedRecordImpl<>(this);
    }

    @Override
    public void merge(NamespacedRecord<? extends Namespace, V> otherRecord) {
        // I haven't figured out how to clean this up yet
        NamespacedRecordImpl<K, V> other = (NamespacedRecordImpl<K, V>) otherRecord;
        for (Map.Entry<K, V> kvEntry : other.delegate.entrySet()) {
            // In order to keep our behavior consistent with how memoized transformations behave,
            // we only add the operation if it's not already registered
            // Such that if the parent's transformation differ from the child's transformation, the parent's win
            this.delegate.putIfAbsent(kvEntry.getKey(), kvEntry.getValue());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NamespacedRecordImpl<?, ?> that = (NamespacedRecordImpl<?, ?>) o;
        return Objects.equals(delegate, that.delegate);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(delegate);
    }

    @Override
    public Map<K, V> asMap() {
        return this.delegate;
    }

    @Override
    public int size() {
        return delegate.size();
    }
}
