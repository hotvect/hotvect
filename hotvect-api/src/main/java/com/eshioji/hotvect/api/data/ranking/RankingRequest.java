package com.eshioji.hotvect.api.data.ranking;

import java.util.List;

public class RankingRequest<SHARED, ACTION> {
    private final SHARED shared;
    private final List<ACTION> availableActions;

    public RankingRequest(SHARED shared, List<ACTION> availableActions) {
        this.shared = shared;
        this.availableActions = availableActions;
    }

    public SHARED getShared() {
        return shared;
    }

    public List<ACTION> getAvailableActions() {
        return availableActions;
    }
}
