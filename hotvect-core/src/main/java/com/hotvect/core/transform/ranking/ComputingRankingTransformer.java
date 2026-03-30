package com.hotvect.core.transform.ranking;

import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.Computable;
import com.hotvect.core.transform.Computing;
import com.hotvect.core.transform.TransformationMetadata;

import java.util.List;
import java.util.SortedSet;

public interface ComputingRankingTransformer<SHARED, ACTION> extends RankingTransformer<SHARED, ACTION> {

    @Override
    default List<TransformedAction<ACTION>> transform(RankingRequest<SHARED, ACTION> rankingRequest) {
        ComputingRankingRequest<SHARED, ACTION> memoized = this.prepare(rankingRequest);
        return this.transform(memoized);
    }

    List<TransformedAction<ACTION>> transform(ComputingRankingRequest<SHARED, ACTION> rankingRequest);


    @Override
    SortedSet<Namespace> getUsedFeatures();

    ComputingRankingRequest<SHARED, ACTION> prepare(String exampleId, SHARED shared, List<Computable<ACTION>> actions);

    ComputingRankingRequest<SHARED, ACTION> prepare(RankingRequest<SHARED, ACTION> rankingRequest);

    ComputingRankingRequest<SHARED, ACTION> prepare(ComputingRankingRequest<SHARED, ACTION> computingRankingRequest);

    List<TransformationMetadata> getTransformationMetadata();

}

