package com.hotvect.api.data.ranking;

import java.util.List;
import java.util.Objects;

public class RankingRequest<SHARED, ACTION> {

    private final String exampleId;
    private final SHARED shared;
    private final List<ACTION> availableActions;

    public RankingRequest(String exampleId, SHARED shared, List<ACTION> availableActions) {
        this.exampleId = exampleId;
        this.shared = shared;
        this.availableActions = availableActions;
    }


    public SHARED getShared() {
        return shared;
    }

    public List<ACTION> getAvailableActions() {
        return availableActions;
    }

    public String getExampleId() {
        return exampleId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RankingRequest<?, ?> that = (RankingRequest<?, ?>) o;
        return Objects.equals(exampleId, that.exampleId) && Objects.equals(shared, that.shared) && Objects.equals(availableActions, that.availableActions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exampleId, shared, availableActions);
    }

    @Override
    public String toString() {
        return "RankingRequest{" +
                "exampleId='" + exampleId + '\'' +
                ", shared=" + shared +
                ", availableActions=" + availableActions +
                '}';
    }
}
