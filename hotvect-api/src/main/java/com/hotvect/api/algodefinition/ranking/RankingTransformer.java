package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;

public interface RankingTransformer<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, List<NamespacedRecord<Namespace, Object>>> {

    @Deprecated(forRemoval = true)
    @Override
    default List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<SHARED, ACTION> rankingRequest){
        throw new UnsupportedOperationException("This method is deprecated and should not be used.");
    }

    default List<TransformedAction<ACTION>> transform(RankingRequest<SHARED, ACTION> rankingRequest){
        List<NamespacedRecord<Namespace, Object>> records = this.apply(rankingRequest);
        List<TransformedAction<ACTION>> ret = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            ret.add(TransformedAction.of(rankingRequest.availableActions().get(i), records.get(i)));
        }

        return ret;
    }

    SortedSet<? extends Namespace> getUsedFeatures();
}
