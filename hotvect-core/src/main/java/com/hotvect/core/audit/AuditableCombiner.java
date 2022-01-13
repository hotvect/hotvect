package com.hotvect.core.audit;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.core.combine.Combiner;

import java.util.List;
import java.util.Map;

public interface AuditableCombiner<FEATURE extends Enum<FEATURE> &FeatureNamespace> extends Combiner<FEATURE> {
    ThreadLocal<Map<Integer, List<RawFeatureName>>> enableAudit(ThreadLocal<Map<HashedFeatureName, RawFeatureName>> featureName2SourceRawValue);
}
