package com.hotvect.catboost;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.core.transform.Computable;

import java.util.List;

final class RankingTestData {
    private RankingTestData() {
    }

    static RankingRequest<String, String> request(String exampleId, String shared, List<String> actions) {
        return RankingRequest.ofAvailableActions(
                exampleId,
                shared,
                actions.stream()
                        .map(action -> AvailableAction.of(action, action))
                        .toList()
        );
    }

    static RankingRequest<String, String> request(String exampleId, String shared, String... actions) {
        return request(exampleId, shared, List.of(actions));
    }

    static RankingRequest<String, String> requestFromComputableActions(
            String exampleId,
            String shared,
            List<Computable<String>> actions
    ) {
        return new RankingRequest<>(
                exampleId,
                shared,
                actions.stream()
                        .map(Computable::getOriginalInput)
                        .toList()
        );
    }
}
