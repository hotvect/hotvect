package com.hotvect.api.data;

/**
 * Interface for requests that contain feature store responses for offline processing.
 * Extends the base Request interface and adds feature store container access.
 * 
 * Examples are always offline (they contain outcomes for training/evaluation), 
 * so all Example implementations should use requests that implement this interface.
 */
public interface OfflineRequest<SHARED> extends Request<SHARED> {
    /**
     * Returns the feature store responses available for this offline request.
     * @return container with feature store responses by view name
     */
    FeatureStoreResponseContainer featureStoreResponseContainer();
}