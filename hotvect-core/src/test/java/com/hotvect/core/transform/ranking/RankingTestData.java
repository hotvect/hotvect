package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RankingTestData {
    private RankingTestData() {
    }

    static <SHARED, ACTION> RankingRequest<SHARED, ACTION> rankingRequest(
            String exampleId,
            SHARED shared,
            List<ACTION> actions
    ) {
        return RankingRequest.ofAvailableActions(exampleId, shared, actions(actions));
    }

    static <ACTION> List<AvailableAction<ACTION>> actions(List<ACTION> rawActions) {
        List<AvailableAction<ACTION>> ret = new ArrayList<>(rawActions.size());
        for (int i = 0; i < rawActions.size(); i++) {
            ret.add(AvailableAction.of("action-" + i, rawActions.get(i)));
        }
        return Collections.unmodifiableList(ret);
    }
}
