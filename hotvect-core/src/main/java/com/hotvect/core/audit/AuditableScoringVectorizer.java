package com.hotvect.core.audit;


import com.hotvect.api.vectorization.ScoringVectorizer;

import java.util.List;
import java.util.Map;

public interface AuditableScoringVectorizer<RECORD> extends ScoringVectorizer<RECORD> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
