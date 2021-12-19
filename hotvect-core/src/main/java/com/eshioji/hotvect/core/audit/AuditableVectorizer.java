package com.eshioji.hotvect.core.audit;


import com.eshioji.hotvect.api.vectorization.Vectorizer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public interface AuditableVectorizer<R> extends Vectorizer<R> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
