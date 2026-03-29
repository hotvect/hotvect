package com.hotvect.core.transform.ranking;

import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;

import java.util.List;
import java.util.stream.Stream;

/**
 * A {@link RankingTransformer} that exposes a stream-based transformation API.
 *
 * <p>Implementations should provide {@link #transformStream(RankingRequest)} to return a stream of per-action transformations.
 * The default {@link #transform(RankingRequest)} collects the results into a list.
 * Callers may choose sequential or parallel execution on the returned stream via {@link Stream#parallel()}.</p>
 */
public interface StreamingRankingTransformer<SHARED, ACTION> extends RankingTransformer<SHARED, ACTION> {
    /**
     * Returns a stream of transformed action batches.
     *
     * <p>Implementations are expected to compute shared features once (per request) before per-action
     * streaming begins.</p>
     */
    Stream<TransformedAction<ACTION>> transformStream(RankingRequest<SHARED, ACTION> request);

    Stream<List<TransformedAction<ACTION>>> transformBatchStream(RankingRequest<SHARED, ACTION> request);

    @Override
    default List<TransformedAction<ACTION>> transform(RankingRequest<SHARED, ACTION> request) {
        return transformStream(request).toList();
    }
}
