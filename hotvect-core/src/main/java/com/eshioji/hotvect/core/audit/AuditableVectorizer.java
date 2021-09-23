package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.core.vectorization.Vectorizer;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

public interface AuditableVectorizer<R> extends Vectorizer<R> {
    ConcurrentMap<Integer, List<RawFeatureName>> enableAudit();
}
