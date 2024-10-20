package com.hotvect.api.data.topk;

import com.hotvect.api.transformation.memoization.Computing;

import java.util.Objects;

public class AvailableAction<ACTION> {
    private final String actionId;
    private final Computing<ACTION> action;

    public AvailableAction(String actionId, Computing<ACTION> action) {
        this.actionId = actionId;
        this.action = action;
    }

    public String getActionId() {
        return actionId;
    }

    public Computing<ACTION> getAction() {
        return action;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AvailableAction<?> that = (AvailableAction<?>) o;
        return Objects.equals(actionId, that.actionId) && Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actionId, action);
    }

    @Override
    public String toString() {
        return "AvailableAction{" +
                "actionId='" + actionId + '\'' +
                ", action=" + action +
                '}';
    }
}
