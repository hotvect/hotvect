package com.eshioji.hotvect.api.algodefinition.common;

import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;

import java.util.function.Supplier;

public interface RewardFunctionFactory<OUTCOME> extends Supplier<RewardFunction<OUTCOME>> {
    @Override
    RewardFunction<OUTCOME> get();
}
