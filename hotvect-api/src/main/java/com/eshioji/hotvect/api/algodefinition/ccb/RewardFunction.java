package com.eshioji.hotvect.api.algodefinition.ccb;

import java.util.function.ToDoubleFunction;

public interface RewardFunction<OUTCOME> extends ToDoubleFunction<OUTCOME> {
    @Override
    double applyAsDouble(OUTCOME outcome);
}
