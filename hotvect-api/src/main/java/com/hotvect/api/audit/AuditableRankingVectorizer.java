package com.hotvect.api.audit;

import com.hotvect.api.algodefinition.ranking.RankingVectorizer;

import java.util.List;
import java.util.Map;

@Deprecated(forRemoval = true)
public interface AuditableRankingVectorizer<SHARED, ACTION> extends RankingVectorizer<SHARED, ACTION> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit();
}
