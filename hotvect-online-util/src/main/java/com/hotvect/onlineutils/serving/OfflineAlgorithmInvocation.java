package com.hotvect.onlineutils.serving;

import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.Response;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.topk.TopKRequest;
import java.util.Objects;

/** Request dispatch shared by the owning fixed and EMS offline runtimes. */
final class OfflineAlgorithmInvocation {
    private OfflineAlgorithmInvocation() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static <SHARED, ACTION> Response<ACTION> invoke(
            Algorithm algorithm, RankingRequest<SHARED, ACTION> request) {
        Objects.requireNonNull(request, "request must not be null");
        return switch (algorithm) {
            case Ranker ranker -> ranker.rank(request);
            case BulkScorer scorer -> scorer.score(request);
            default -> throw new IllegalArgumentException("Selected algorithm does not accept ranking requests");
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static <SHARED, ACTION> Response<ACTION> invoke(
            Algorithm algorithm, TopKRequest<SHARED> request) {
        Objects.requireNonNull(request, "request must not be null");
        if (algorithm instanceof TopK topK) {
            return topK.apply(request);
        }
        throw new IllegalArgumentException("Selected algorithm does not accept TopK requests");
    }
}
