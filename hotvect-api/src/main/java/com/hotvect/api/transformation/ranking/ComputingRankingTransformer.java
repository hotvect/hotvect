package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.transformation.Computing;
import com.hotvect.api.transformation.TransformationMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

@Deprecated(forRemoval = true)
public interface ComputingRankingTransformer<SHARED, ACTION> extends RankingTransformer<SHARED, ACTION> {

    /**
     * Please prefer to call {@link #apply(ComputingRankingRequest)} for better performance
     */
    @Deprecated
    @Override
    default List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        ComputingRankingRequest<SHARED, ACTION> memoized = this.prepare(rankingRequest);
        return this.apply(memoized);
    }

    @Deprecated(forRemoval = true)
    default List<NamespacedRecord<Namespace, Object>> apply(ComputingRankingRequest<SHARED, ACTION> input){
        throw new UnsupportedOperationException();
    }

    @Override
    default List<TransformedAction<ACTION>> transform(RankingRequest<SHARED, ACTION> rankingRequest) {
        ComputingRankingRequest<SHARED, ACTION> memoized = this.prepare(rankingRequest);
        return this.transform(memoized);
    }

    default List<TransformedAction<ACTION>> transform(ComputingRankingRequest<SHARED, ACTION> rankingRequest){
        List<NamespacedRecord<Namespace, Object>> records = this.apply(rankingRequest);
        List<TransformedAction<ACTION>> ret = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            ret.add(TransformedAction.of(rankingRequest.rankingRequest().availableActions().get(i), records.get(i)));
        }

        return ret;
    }


    @Override
    SortedSet<Namespace> getUsedFeatures();

    ComputingRankingRequest<SHARED, ACTION> prepare(String exampleId, SHARED shared, List<Computing<ACTION>> actions);

    ComputingRankingRequest<SHARED, ACTION> prepare(RankingRequest<SHARED, ACTION> rankingRequest);

    ComputingRankingRequest<SHARED, ACTION> prepare(ComputingRankingRequest<SHARED, ACTION> computingRankingRequest);

    List<TransformationMetadata> getTransformationMetadata();

}
