package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.core.combine.Combiner;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

public interface AuditableCombiner<H extends Enum<H> &FeatureNamespace> extends Combiner<H> {
    ConcurrentMap<Integer, List<RawFeatureName>> enableAudit(ConcurrentMap<HashedFeatureName, RawFeatureName> featureName2SourceRawValue);
}
