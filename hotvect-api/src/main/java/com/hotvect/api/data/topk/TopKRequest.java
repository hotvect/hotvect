package com.hotvect.api.data.topk;

import com.hotvect.api.data.Request;

import java.time.Instant;
import java.util.Objects;

public class TopKRequest<SHARED> implements Request<SHARED> {
    private final String exampleId;
    private final Instant occurredAt;
    private final SHARED shared;
    private final int k;

    public TopKRequest(String exampleId, Instant occurredAt, SHARED shared, int k) {
        this.exampleId = exampleId;
        this.occurredAt = occurredAt;
        this.shared = shared;
        this.k = k;
    }

    public String exampleId() {
        return exampleId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public SHARED shared() {
        return shared;
    }

    public int k() {
        return k;
    }

    /**
     * @deprecated Use {@link #exampleId()} instead
     */
    @Deprecated(forRemoval = true)
    @Override
    public String getExampleId() {
        return exampleId;
    }

    /**
     * @deprecated Use {@link #shared()} instead
     */
    @Deprecated(forRemoval = true)
    @Override
    public SHARED getShared() {
        return shared;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopKRequest<?> that = (TopKRequest<?>) o;
        return k == that.k &&
               Objects.equals(exampleId, that.exampleId) &&
               Objects.equals(occurredAt, that.occurredAt) &&
               Objects.equals(shared, that.shared);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exampleId, occurredAt, shared, k);
    }

    @Override
    public String toString() {
        return "TopKRequest{" +
                "exampleId='" + exampleId + '\'' +
                ", occurredAt=" + occurredAt +
                ", shared=" + shared +
                ", k=" + k +
                '}';
    }
}
