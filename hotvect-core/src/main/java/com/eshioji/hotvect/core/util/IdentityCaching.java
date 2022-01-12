package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.core.transform.ranking.ActionTransformation;
import com.eshioji.hotvect.core.transform.ranking.SharedTransformation;
import com.eshioji.hotvect.core.transform.regression.RecordTransformation;

import java.util.function.Function;

public class IdentityCaching {
    private IdentityCaching() {
    }
    public static <SHARED> SharedTransformation<SHARED> cachedSharedTransformation(SharedTransformation<SHARED> toCache) {
        return r -> cachedFunction(toCache).apply(r);
    }

    public static <ACTION> ActionTransformation<ACTION> cachedActionTransformation(ActionTransformation<ACTION> toCache) {
        return r -> cachedFunction(toCache).apply(r);
    }

    public static <RECORD> RecordTransformation<RECORD> cachedTransformation(RecordTransformation<RECORD> toCache) {
        return r -> cachedFunction(toCache).apply(r);
    }


    public static <FROM, TO> Function<FROM, TO> cachedFunction(Function<FROM, TO> toCache) {
        ThreadLocal<Pair<FROM, TO>> cache = ThreadLocal.withInitial(() -> Pair.of(null, null));
        Function<FROM, TO> ret = f -> {
                    var cached = cache.get();
                    if (cached._1 == f) {
                        return cached._2;
                    } else {
                        var newKey = f;
                        var newVal = toCache.apply(f);
                        cache.set(Pair.of(newKey, newVal));
                        return newVal;
                    }
                };
        return ret;
    }
}