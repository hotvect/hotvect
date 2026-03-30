package com.hotvect.api.data.ranking;

import com.hotvect.api.data.common.Example;

import java.util.List;

public record RankingExample<SHARED, ACTION, OUTCOME>(
        String exampleId,
        OfflineRankingRequest<SHARED, ACTION> request,
        List<RankingOutcome<OUTCOME, ACTION>> outcomes
) implements Example<OfflineRankingRequest<SHARED, ACTION>, RankingOutcome<OUTCOME, ACTION>> {

    /**
     * Legacy constructor for backward compatibility with v64.4.0 and older versions.
     * This constructor matches the old 3-parameter signature used by legacy algorithms.
     * For non-OfflineRankingRequest instances, it creates a wrapper with empty feature store container.
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
                 OfflineRankingRequest.newOfflineRankingRequest(request.exampleId(), request.shared(), request.availableActions(), com.hotvect.api.data.FeatureStoreResponseContainer.empty()),
             outcomes);
    }

    /**
     * Backward compatibility method for legacy algos
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
