package com.hotvect.api.data.topk;

import java.util.Objects;

public class TopKRequest<SHARED, ACTION> {
    private final String exampleId;
    private final SHARED shared;

    private final int k;

    private final long occurredAtUnixMs;

    public TopKRequest(String exampleId, long occurredAtUnixMs, SHARED shared, int k) {
        this.exampleId = exampleId;
        this.shared = shared;
        this.occurredAtUnixMs = occurredAtUnixMs;
        this.k = k;
    }


    public SHARED getShared() {
        return shared;
    }

    public String getExampleId() {
        return exampleId;
    }

    public long getOccurredAtUnixMs() {
        return occurredAtUnixMs;
    }

    public int getK() {
        return k;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopKRequest<?, ?> that = (TopKRequest<?, ?>) o;
        return k == that.k && occurredAtUnixMs == that.occurredAtUnixMs && Objects.equals(exampleId, that.exampleId) && Objects.equals(shared, that.shared);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exampleId, shared, k, occurredAtUnixMs);
    }

    @Override
    public String toString() {
        return "TopKRequest{" +
                "exampleId='" + exampleId + '\'' +
                ", shared=" + shared +
                ", k=" + k +
                ", occurredAtUnixMs=" + occurredAtUnixMs +
                '}';
    }
}
