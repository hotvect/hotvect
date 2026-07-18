package com.hotvect.offlineutils.export;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.hotvect.utils.AdditionalProperties.mergeAdditionalProperties;

/**
 * Offline-util-local BulkScorer-to-Ranker adapter.
 *
 * <p>This intentionally stays in offline-util so the module does not need a dependency on
 * hotvect-core just to rank bulk scores for predict output formatting.</p>
 *
 * <p>Do not replace this with {@code com.hotvect.core.rank.BulkScoreGreedyRanker} unless the
 * module boundary itself is being changed on purpose. Keeping this local avoids reintroducing a
 * direct offline-util -> core dependency just for predict-time ranking/output formatting.</p>
 */
public class BulkScoreGreedyRanker<SHARED, ACTION> implements Ranker<SHARED, ACTION> {
    private static final HashFunction TIE_BREAK_HASH_FUN = Hashing.murmur3_32_fixed(0x2510C4E5);

    private final BulkScorer<SHARED, ACTION> bulkScorer;

    public BulkScoreGreedyRanker(BulkScorer<SHARED, ACTION> bulkScorer) {
        this.bulkScorer = bulkScorer;
    }

    @Override
    public RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> request) {
        var actions = request.actions();
        int numActions = actions.size();
        BulkScoreResponse<ACTION> scoringResponse = this.bulkScorer.score(request);
        List<ScoringDecision<ACTION>> scores = scoringResponse.decisions();
        if (scores.size() != numActions) {
            throw new IllegalArgumentException(
                    "BulkScorer returned " + scores.size() + " scores for " + numActions + " actions"
            );
        }

        List<RankingDecision<ACTION>> decisions = new ArrayList<>(numActions);
        // O(n) ID checks are assertion-only; scorer order is part of the BulkScorer contract.
        assert validateScoreActionIds(request, scores);
        for (int i = 0; i < numActions; ++i) {
            String actionId = actions.get(i).actionId();
            ScoringDecision<ACTION> score = scores.get(i);
            decisions.add(new RankingDecision<>(
                    actionId,
                    i,
                    score.score(),
                    actions.get(i).action(),
                    null,
                    mergeAdditionalProperties(actions.get(i).additionalProperties(), score.additionalProperties())
            ));
        }

        sortDecisions(decisions, request.exampleId());
        return RankingResponse.newResponse(
                decisions,
                scoringResponse.featureStoreResponseContainer(),
                scoringResponse.additionalProperties()
        );
    }

    private static <SHARED, ACTION> boolean validateScoreActionIds(
            RankingRequest<SHARED, ACTION> request,
            List<ScoringDecision<ACTION>> scores
    ) {
        boolean hasActionIds = false;
        boolean hasPositionalScores = false;
        for (int i = 0; i < scores.size(); i++) {
            String scoreActionId = scores.get(i).actionId();
            if (scoreActionId == null) {
                hasPositionalScores = true;
            } else {
                hasActionIds = true;
                String expectedActionId = request.actions().get(i).actionId();
                checkArgument(
                        scoreActionId.equals(expectedActionId),
                        "BulkScorer must preserve request action order; score at position %s has action id %s, expected %s",
                        i,
                        scoreActionId,
                        expectedActionId
                );
            }
        }
        if (!hasActionIds) {
            return true;
        }
        checkArgument(
                !hasPositionalScores,
                "BulkScorer returned a mix of action-id and positional scores"
        );
        return true;
    }

    @Override
    public void close() throws Exception {
        bulkScorer.close();
    }

    private static long stableTieBreakKey(String exampleId, String actionId) {
        checkArgument(exampleId != null && !exampleId.isBlank(), "exampleId cannot be null or blank");
        checkArgument(actionId != null && !actionId.isBlank(), "actionId cannot be null or blank");

        return TIE_BREAK_HASH_FUN
                .newHasher()
                .putInt(exampleId.length())
                .putUnencodedChars(exampleId)
                .putInt(actionId.length())
                .putUnencodedChars(actionId)
                .hash()
                .padToLong();
    }

    private static <ACTION> void sortDecisions(List<RankingDecision<ACTION>> decisions, String exampleId) {
        decisions.sort((left, right) -> {
            if (left == right) {
                return 0;
            }

            int byScore = Double.compare(right.score(), left.score());
            if (byScore != 0) {
                return byScore;
            }
            int byTieBreakKey = Long.compareUnsigned(
                    stableTieBreakKey(exampleId, left.actionId()),
                    stableTieBreakKey(exampleId, right.actionId())
            );
            if (byTieBreakKey != 0) {
                return byTieBreakKey;
            }
            return left.actionId().compareTo(right.actionId());
        });
    }
}
