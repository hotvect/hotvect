package com.hotvect.api.algodefinition;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class AlgorithmParameterMetadata {
    private final String parameterId;
    private final Instant ranAt;

    private final Optional<Instant> lastTrainingTime;

    private final AlgorithmId algorithmId;

    public AlgorithmParameterMetadata(String algorithmName, String algorithmVersion, String parameterId, Instant ranAt, Optional<Instant> lastTrainingTime) {
        this.lastTrainingTime = lastTrainingTime;
        this.algorithmId = new AlgorithmId(algorithmName, algorithmVersion);
        this.parameterId = parameterId;
        this.ranAt = ranAt;
    }

    public String getParameterId() {
        return parameterId;
    }

    public Instant getRanAt() {
        return ranAt;
    }

    public AlgorithmId getAlgorithmId() {
        return algorithmId;
    }

    public Optional<Instant> getLastTrainingTime() {
        return lastTrainingTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AlgorithmParameterMetadata that = (AlgorithmParameterMetadata) o;
        return Objects.equals(parameterId, that.parameterId) && Objects.equals(ranAt, that.ranAt) && Objects.equals(lastTrainingTime, that.lastTrainingTime) && Objects.equals(algorithmId, that.algorithmId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameterId, ranAt, lastTrainingTime, algorithmId);
    }

    @Override
    public String toString() {
        return "AlgorithmParameterMetadata{" +
                "parameterId='" + parameterId + '\'' +
                ", ranAt=" + ranAt +
                ", lastTrainingDataUpdate=" + lastTrainingTime +
                ", algorithmId=" + algorithmId +
                '}';
    }
}
