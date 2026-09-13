package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import java.util.function.Supplier;

/**
 * Runtime-owned interning for statically shared graph nodes.
 *
 * <p>The interner evaluates the supplied graph factory only when the shared algorithm name and
 * version are absent.
 * A returned node is an opaque lifetime handle owned and closed by its parent graph.
 * Direct graph loading deliberately does not use this interface.</p>
 */
public interface SharedNodeInterner {

    /** Returns the node for one canonical algorithm identity, constructing it only on a cache miss. */
    SharedNode intern(AlgorithmId algorithmId, Supplier<AlgorithmGraph<?>> graphFactory);

    /** One retained shared node and its runtime instance. */
    interface SharedNode extends AutoCloseable {
        AlgorithmInstance<?> instance();

        AlgorithmRuntimeId runtimeId();

        /** Releases this handle's retained ownership of the shared node. */
        @Override
        void close();
    }
}
