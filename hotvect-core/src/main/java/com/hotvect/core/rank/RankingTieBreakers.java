package com.hotvect.core.rank;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.hotvect.api.data.ranking.RankingDecision;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

public final class RankingTieBreakers {
    private static final HashFunction TIE_BREAK_HASH_FUN = Hashing.murmur3_32_fixed(0x2510C4E5);

    private RankingTieBreakers() {
    }

    public static long stableTieBreakKey(String exampleId, String actionId) {
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

    public static int compare(long left, long right) {
        return Long.compareUnsigned(left, right);
    }

    static <ACTION> void sortDecisions(List<RankingDecision<ACTION>> decisions, String exampleId) {
        decisions.sort((left, right) -> {
            if (left == right) {
                return 0;
            }

            int byScore = Double.compare(right.score(), left.score());
            if (byScore != 0) {
                return byScore;
            }
            int byTieBreakKey = RankingTieBreakers.compare(
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
