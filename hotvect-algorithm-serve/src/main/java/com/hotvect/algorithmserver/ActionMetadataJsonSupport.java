package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ActionMetadataJsonSupport {
    private ActionMetadataJsonSupport() {
    }

    public static void putActionDisplayMetadata(
            ObjectNode dest,
            String actionId,
            ActionMetadataLookup.ActionMetadata meta) {
        putActionDisplayMetadata(dest, actionId, meta, false);
    }

    public static void putActionDisplayMetadata(
            ObjectNode dest,
            String actionId,
            ActionMetadataLookup.ActionMetadata meta,
            boolean fallbackNameToActionId) {
        JsonFieldSupport.putStringOrNull(
                dest,
                "action_name",
                actionNameOrNull(actionId, meta, fallbackNameToActionId));
        JsonFieldSupport.putStringOrNull(dest, "action_image_url", actionImageUrlOrNull(meta));
    }

    public static String actionNameOrNull(
            String actionId,
            ActionMetadataLookup.ActionMetadata meta,
            boolean fallbackNameToActionId) {
        String actionName = meta == null ? null : JsonFieldSupport.blankToNull(meta.actionName());
        if (actionName == null && fallbackNameToActionId) {
            return JsonFieldSupport.blankToNull(actionId);
        }
        return actionName;
    }

    public static String actionImageUrlOrNull(ActionMetadataLookup.ActionMetadata meta) {
        return meta == null ? null : JsonFieldSupport.blankToNull(meta.actionImageUrl());
    }
}
