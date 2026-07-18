package com.hotvect.algorithmserver;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

public interface ActionMetadataLookup extends AutoCloseable {
    static ActionMetadataLookup empty() {
        return EmptyActionMetadataLookup.INSTANCE;
    }

    boolean isEnabled();

    int size();

    Map<String, ActionMetadata> getAllIfEnabled(Collection<String> actionIds);

    @Override
    default void close() {
    }

    record ActionMetadata(String actionId, String actionName, String actionImageUrl) {
        public ActionMetadata {
            Objects.requireNonNull(actionId);
        }
    }

    enum EmptyActionMetadataLookup implements ActionMetadataLookup {
        INSTANCE;

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public Map<String, ActionMetadata> getAllIfEnabled(Collection<String> actionIds) {
            Objects.requireNonNull(actionIds);
            return Map.of();
        }
    }
}
