package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.policies.CcbRankingPolicy;
import com.eshioji.hotvect.api.vectorization.ccb.ActionVectorizer;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiFunction;

public interface CcbPolicyFactory<SHARED, ACTION> extends BiFunction<ActionVectorizer<SHARED, ACTION>, Map<String, InputStream>, CcbRankingPolicy<SHARED, ACTION>> {
}
