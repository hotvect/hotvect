package com.hotvect.api.data.ranking;

import com.hotvect.api.data.Request;

import java.util.List;
import java.util.Objects;

public class RankingRequest<SHARED, ACTION> implements Request<SHARED> {
    private final String exampleId;
    private final SHARED shared;
    private final List<ACTION> availableActions;

    public RankingRequest(String exampleId, SHARED shared, List<ACTION> availableActions) {
        this.exampleId = exampleId;
        this.shared = shared;
        this.availableActions = availableActions;
    }

    public String exampleId() {
        return exampleId;
    }

    public SHARED shared() {
        return shared;
    }

    public List<ACTION> availableActions() {
        return availableActions;
    }

    /**
     * For backward compatibility.
     * @return
     * @deprecated Use {@link #availableActions()} instead
     */
    @Deprecated(forRemoval = true)
    public List<ACTION> getAvailableActions() {
        return availableActions;
    }

    /**
     * @deprecated Use {@link #exampleId()} instead
     */
    @Deprecated(forRemoval = true)
    @Override
    public String getExampleId() {
        return exampleId;
    }

    /**
     * @deprecated Use {@link #shared()} instead
     */
    @Deprecated(forRemoval = true)
    @Override
    public SHARED getShared() {
        return shared;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RankingRequest<?, ?> that = (RankingRequest<?, ?>) o;
        return Objects.equals(exampleId, that.exampleId) &&
               Objects.equals(shared, that.shared) &&
               Objects.equals(availableActions, that.availableActions);
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
