package com.hotvect.core.transform.ranking;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.ScoringDecision;

import java.util.List;

public interface ComputingBulkScorer<SHARED, ACTION> extends BulkScorer<SHARED, ACTION> {
    List<ScoringDecision<ACTION>> bulkScore(ComputingRankingRequest<SHARED, ACTION> rankingRequest);
}
