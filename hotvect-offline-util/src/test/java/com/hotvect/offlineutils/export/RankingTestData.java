package com.hotvect.offlineutils.export;

import com.hotvect.api.data.AvailableAction;

import java.util.List;

final class RankingTestData {
    private RankingTestData() {
    }

    static List<AvailableAction<String>> actions(String... actions) {
        return List.of(actions).stream()
                .map(action -> AvailableAction.of(action, action))
                .toList();
    }
}
