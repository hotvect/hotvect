package com.eshioji.hotvect.api.data.raw.ccb;

import java.util.List;
import java.util.Set;

public class Request<SHARED, ACTION> {
    private final SHARED shared;
    private final List<ACTION> availableActions;

    public Request(SHARED shared, List<ACTION> availableActions) {
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
