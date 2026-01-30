package com.hotvect.api.data;

import java.util.Map;

/**
 * Base interface for all decision types in the hotvect API.
 *
 * @param <ACTION> The type of action associated with this decision
 */
public interface Decision<ACTION> {
    String actionId();
    Double score();
    ACTION action();
    Double probability();
    Map<String, Object> additionalProperties();
}
