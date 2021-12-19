package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.core.combine.Combiner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public interface AuditableCombiner<H extends Enum<H> &FeatureNamespace> extends Combiner<H> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit(ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue);
}
