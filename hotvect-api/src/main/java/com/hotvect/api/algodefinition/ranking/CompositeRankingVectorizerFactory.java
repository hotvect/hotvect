package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.CompositeVectorizerFactory;

public interface CompositeRankingVectorizerFactory<SHARED, ACTION> extends CompositeVectorizerFactory<RankingVectorizer<SHARED, ACTION>> {
}
