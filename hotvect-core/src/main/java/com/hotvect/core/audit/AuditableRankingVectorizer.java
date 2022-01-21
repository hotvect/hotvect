package com.hotvect.core.audit;

import com.hotvect.api.vectorization.RankingVectorizer;

import java.util.List;
import java.util.Map;

public interface AuditableRankingVectorizer<SHARED, ACTION> extends RankingVectorizer<SHARED, ACTION> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
