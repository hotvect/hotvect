package com.hotvect.api.data.common;

import com.hotvect.api.data.OfflineRequest;

import java.util.List;

/**
 * Base interface for all example types that contain a request and outcomes.
 * Examples are always offline data (they contain outcomes for training/evaluation),
 * so they always use OfflineRequest implementations that contain feature store responses.
 *
 * @param <REQUEST> The type of offline request (extends OfflineRequest)
 * @param <OUTCOME> The type of outcome (extends Outcome)
 */
public interface Example<REQUEST extends OfflineRequest<?>, OUTCOME extends Outcome<?, ?>> {
    /**
     * Returns the unique identifier for this example.
     *
     * @return the example ID
     */
    String exampleId();

    /**
     * Returns the offline request associated with this example.
     * The request contains both the input data and feature store responses.
     *
     * @return the offline request
     */
    REQUEST request();

    /**
     * Returns the list of outcomes for this example.
     *
     * @return the outcomes
     */
    List<OUTCOME> outcomes();
}
