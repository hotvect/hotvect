package com.eshioji.hotvect.api.data.raw.ccb;

import java.util.List;

public class Options<SHARED, ACTION> {
    private final SHARED shared;
    private final List<ACTION> actions;

    public Options(SHARED shared, List<ACTION> actions) {
        this.shared = shared;
        this.actions = actions;
    }

    public SHARED getShared() {
        return shared;
    }

    public List<ACTION> getActions() {
        return actions;
    }
}
