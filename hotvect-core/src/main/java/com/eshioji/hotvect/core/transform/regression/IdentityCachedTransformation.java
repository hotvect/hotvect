package com.eshioji.hotvect.core.transform.regression;

import com.eshioji.hotvect.api.data.RawValue;

import java.util.function.Function;

public class IdentityCachedTransformation<V> implements Transformation<V> {
    private final Function<V, RawValue> functionToCache;
    private final ThreadLocal<CacheEntry> cache = ThreadLocal.withInitial(CacheEntry::new);

    private IdentityCachedTransformation(Function<V, RawValue> functionToCache) {
        this.functionToCache = functionToCache;
    }

    private class CacheEntry {
        V key;
        RawValue cached;
    }

    @Override
    public RawValue apply(V v) {
        CacheEntry cached = cache.get();

        // Identity comparison is intentional
        if (v != cached.key) {
            cached.key = v;
            cached.cached = functionToCache.apply(v);
        }
        return cached.cached;
    }

    public static <R> Transformation<R> cached(Function<R, RawValue> toCache) {
        return new IdentityCachedTransformation<>(toCache);
    }
}

