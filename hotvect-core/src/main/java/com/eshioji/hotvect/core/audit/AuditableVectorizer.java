package com.eshioji.hotvect.core.audit;


import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;

import java.util.List;
import java.util.Map;

public interface AuditableVectorizer<R> extends Vectorizer<R> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
