package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;

import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;

public interface RankingTransformer<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, List<NamespacedRecord<FeatureNamespace, RawValue>>> {
    @Override
    List<NamespacedRecord<FeatureNamespace, RawValue>> apply(RankingRequest<SHARED, ACTION> rankingRequest);

    SortedSet<? extends FeatureNamespace> getUsedFeatures();
}
