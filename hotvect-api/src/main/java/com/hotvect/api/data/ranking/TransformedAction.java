package com.hotvect.api.data.ranking;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;

import java.util.Map;

/**
 * A record representing a transformed action with the associated domain model,
 * an ACTION reference, and any additional properties.
 *
 * @param <ACTION> The type parameter for the action (e.g., enumeration or class)
 */
public record TransformedAction<ACTION>(
        ACTION action,
        NamespacedRecord<Namespace, Object> transformed,
        Map<String, Object> additionalProperties
) {
    /**
     * Static factory method that constructs a TransformedAction with an action
     * and a transformed record, using an empty map for additionalProperties.
     *
     * @param action the action
     * @param transformed a NamespacedRecord containing any data relevant to the action
     * @return a TransformedAction instance carrying an action and a transformed record
     */
    public static <ACTION> TransformedAction<ACTION> of(
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed) {
        return new TransformedAction<>(action, transformed, ImmutableMap.of());
    }

    /**
     * Static factory method that constructs a TransformedAction with an action,
     * a transformed record, and specified additionalProperties.
     *
     * @param action the action
     * @param transformed a NamespacedRecord containing data relevant to the action
     * @param additionalProperties any extra data you wish to associate with this action
     * @return a TransformedAction instance carrying an action, a transformed record,
     *         and additional properties
     */
    public static <ACTION> TransformedAction<ACTION> of(
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed,
            Map<String, Object> additionalProperties) {
        return new TransformedAction<>(action, transformed, additionalProperties);
    }
}