package com.hotvect.api.data;

public interface Request<SHARED> {
    /**
     * Returns the ID of the example associated with this request.
     *
     * @return the example ID
     */
    String exampleId();

    /**
     * Returns the shared data associated with this request.
     *
     * @return the shared data
     */
    SHARED shared();

    /**
     * @deprecated Use {@link #exampleId()} instead
     */
    @Deprecated(forRemoval = true)
    default String getExampleId() {
        return exampleId();
    }

    /**
     * @deprecated Use {@link #shared()} instead
     */
    @Deprecated(forRemoval = true)
    default SHARED getShared() {
        return shared();
    }
}
