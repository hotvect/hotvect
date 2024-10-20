package com.hotvect.vw.audit;

import com.google.common.collect.Sets;
import com.hotvect.api.audit.RawFeatureName;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class VwAuditRecord {
    private final int finalHash;
    private final Set<List<RawFeatureName>> rawFeatureNames;
    private final double weight;

    public VwAuditRecord(int finalHash, double weight) {
        this.finalHash = finalHash;
        this.rawFeatureNames = Sets.newConcurrentHashSet();
        this.weight = weight;
    }

    public int getFinalHash() {
        return finalHash;
    }

    public Set<List<RawFeatureName>> getRawFeatureNames() {
        return rawFeatureNames;
    }

    public double getWeight() {
        return weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VwAuditRecord that = (VwAuditRecord) o;
        return finalHash == that.finalHash && Double.compare(that.weight, weight) == 0 && rawFeatureNames.equals(that.rawFeatureNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(finalHash, rawFeatureNames, weight);
    }

    @Override
    public String toString() {
        return "VwAuditRecord{" +
                "finalHash=" + finalHash +
                ", rawFeatureNames=" + rawFeatureNames +
                ", weight=" + weight +
                '}';
    }
}
