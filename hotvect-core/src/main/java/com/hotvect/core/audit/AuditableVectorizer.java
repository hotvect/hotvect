package com.hotvect.core.audit;


import com.hotvect.api.vectorization.Vectorizer;

import java.util.List;
import java.util.Map;

public interface AuditableVectorizer<R> extends Vectorizer<R> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
