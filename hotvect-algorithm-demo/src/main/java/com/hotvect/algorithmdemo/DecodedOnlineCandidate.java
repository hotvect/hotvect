package com.hotvect.algorithmdemo;

import java.util.Map;

public record DecodedOnlineCandidate(
        String actionId,
        Double score,
        Map<String, Object> onlineProperties,
        int originalIndex
) {
}
