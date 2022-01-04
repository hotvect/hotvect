package com.eshioji.hotvect.api.policies;

import com.eshioji.hotvect.api.data.raw.ccb.Decision;
import com.eshioji.hotvect.api.data.raw.ccb.Options;

import java.util.List;
import java.util.function.BiFunction;

public interface CcbTopKPolicy<SHARED, ACTION> extends BiFunction<Options<SHARED, ACTION>, Integer, List<Decision>> {
    /**
     * Given {@link Options} and parameter K, come up with a list of {@link Decision} with length k
     * @param options
     * @param k
     * @return
     */
    @Override
    List<Decision> apply(Options<SHARED, ACTION> options, Integer k);
}
