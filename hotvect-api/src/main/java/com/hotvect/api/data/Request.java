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
     * Returns the ID of the example associated with this request.
     *
     * @return the example ID
     * @deprecated Use {@link #exampleId()} instead
     */
    @Deprecated(forRemoval = true)
    String getExampleId();

    /**
     * Returns the shared data associated with this request.
     *
     * @return the shared data
     * @deprecated Use {@link #shared()} instead
     */
    @Deprecated(forRemoval = true)
    SHARED getShared();
}
