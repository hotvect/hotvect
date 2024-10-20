package com.hotvect.api.audit;


import com.hotvect.api.algodefinition.scoring.ScoringVectorizer;

import java.util.List;
import java.util.Map;

@Deprecated(forRemoval = true)
public interface AuditableScoringVectorizer<RECORD> extends ScoringVectorizer<RECORD> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
