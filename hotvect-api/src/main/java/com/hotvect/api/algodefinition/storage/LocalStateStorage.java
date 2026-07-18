package com.hotvect.api.algodefinition.storage;

import java.nio.file.Path;

/**
 * Allocates private filesystem storage for an algorithm instance.
 *
 * <p>Ownership transfers to the caller when a directory is allocated. The caller must remove the directory if
 * construction fails, or from {@code Algorithm.close()} after successful construction. Reachability-based cleanup
 * may be used when an algorithm must remain available to in-flight requests.</p>
 *
 * <p>The runtime owns the directory name and layout. Callers must treat the returned path as opaque.</p>
 */
@FunctionalInterface
public interface LocalStateStorage {
    /**
     * Allocates an absolute, existing, empty directory that is private to the caller.
     */
    Path allocateDirectory();
}
