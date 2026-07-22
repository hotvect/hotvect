package com.hotvect.offlineutils.commandline;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;

import java.util.List;

final class RankingTestData {
    private RankingTestData() {
    }

    static RankingRequest<String, String> request(String exampleId, String shared, String... actions) {
        return RankingRequest.ofAvailableActions(
                exampleId,
                shared,
                List.of(actions).stream()
                        .map(action -> AvailableAction.of(action, action))
                        .toList()
        );
    }
}
