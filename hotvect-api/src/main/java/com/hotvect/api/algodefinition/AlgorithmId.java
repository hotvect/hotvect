package com.hotvect.api.algodefinition;

import java.util.Objects;

public class AlgorithmId {
    private final String algorithmName;
    private final String algorithmVersion;

    public AlgorithmId(String algorithmName, String algorithmVersion) {
        this.algorithmName = algorithmName;
        this.algorithmVersion = algorithmVersion;
    }

    public String getAlgorithmName() {
        return algorithmName;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AlgorithmId that = (AlgorithmId) o;
        return algorithmName.equals(that.algorithmName) && algorithmVersion.equals(that.algorithmVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(algorithmName, algorithmVersion);
    }

    @Override
    public String toString() {
        return "AlgorithmId{" +
                "algorithmName='" + algorithmName + '\'' +
                ", algorithmVersion='" + algorithmVersion + '\'' +
                '}';
    }
}
