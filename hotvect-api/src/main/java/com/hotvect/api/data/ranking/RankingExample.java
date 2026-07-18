package com.hotvect.api.data.ranking;

import com.hotvect.api.data.common.Example;

import java.util.List;

/**
 * Ranking training/evaluation example.
 *
 * <p>Fully labeled examples are positional: {@code outcomes().get(i)} describes
 * {@code request().actions().get(i)}. Encoders that require complete labels enforce both list
 * cardinality and matching action ids at each position. Result-formatting paths may handle partial
 * or unlabeled examples and match the available outcomes by action id.</p>
 */
public record RankingExample<SHARED, ACTION, OUTCOME>(
        String exampleId,
        OfflineRankingRequest<SHARED, ACTION> request,
        List<RankingOutcome<OUTCOME, ACTION>> outcomes
) implements Example<OfflineRankingRequest<SHARED, ACTION>, RankingOutcome<OUTCOME, ACTION>> {

    /**
     * Legacy constructor for backward compatibility with v64.4.0 and older versions.
     * This constructor matches the old 3-parameter signature used by legacy algorithms.
     * For non-OfflineRankingRequest instances, it creates a wrapper with empty feature store container.
     * TODO: Remove after legacy algorithms migrate to the record constructor.
     *
     * @param exampleId the example identifier
     * @param request the ranking request (generic RankingRequest)
     * @param outcomes the list of ranking outcomes
     * @deprecated This constructor is deprecated and marked for removal. Use the record constructor instead.
     */
    @Deprecated(forRemoval = true)
    @SuppressWarnings("unchecked")
    public RankingExample(String exampleId, RankingRequest<SHARED, ACTION> request, List<RankingOutcome<OUTCOME, ACTION>> outcomes) {
        this(exampleId,
                request instanceof OfflineRankingRequest ?
                        (OfflineRankingRequest<SHARED, ACTION>) request :
                        OfflineRankingRequest.ofAvailableActions(
                                request.exampleId(),
                                request.shared(),
                                request.actions(),
                                com.hotvect.api.data.FeatureStoreResponseContainer.empty(),
                                request.additionalProperties()
                        ),
                outcomes);
    }

    /**
     * Backward compatibility method for legacy algos
     * TODO: Remove after legacy algorithms migrate to {@link #request()}.
     * @return
     */
    @Deprecated(forRemoval = true)
    public RankingRequest<SHARED, ACTION> rankingRequest() {
        return request;
    }

    @Deprecated(forRemoval = true)
    public RankingRequest<SHARED, ACTION> getRankingRequest() {
        return request;
    }

    @Deprecated(forRemoval = true)
    public List<RankingOutcome<OUTCOME, ACTION>> getOutcomes() {
        return outcomes;
    }

}
