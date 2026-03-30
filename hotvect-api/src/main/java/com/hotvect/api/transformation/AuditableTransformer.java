package com.hotvect.api.transformation;

/**
 * Interface for transformers that support feature auditing/logging for debugging purposes.
 * When feature auditing is enabled, the transformer captures computed features and makes
 * them available for inspection during prediction.
 *
 * This is primarily used for debugging composite algorithms where data flows through
 * multiple layers and you need to inspect intermediate feature values.
 */
public interface AuditableTransformer {
    /**
     * Enable or disable feature auditing for this transformer.
     *
     * @param enabled true to enable feature auditing, false to disable
     * @param algorithmName the name of the algorithm (used for namespacing features in output)
     */
    void setFeatureAuditEnabled(boolean enabled, String algorithmName);
}
