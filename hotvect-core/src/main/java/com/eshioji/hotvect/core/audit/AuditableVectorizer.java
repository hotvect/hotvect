package com.eshioji.hotvect.core.audit;


import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;

import java.util.List;
import java.util.Map;

public interface AuditableVectorizer<RECORD> extends Vectorizer<RECORD> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
