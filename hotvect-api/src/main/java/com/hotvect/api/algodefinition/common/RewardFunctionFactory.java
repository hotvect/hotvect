package com.hotvect.api.algodefinition.common;

import java.util.function.Supplier;

public interface RewardFunctionFactory<OUTCOME> extends Supplier<RewardFunction<OUTCOME>> {
    @Override
    RewardFunction<OUTCOME> get();
}
