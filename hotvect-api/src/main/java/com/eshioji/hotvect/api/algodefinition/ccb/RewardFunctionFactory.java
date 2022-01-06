package com.eshioji.hotvect.api.algodefinition.ccb;

import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

public interface RewardFunctionFactory<OUTCOME> extends Supplier<RewardFunction<OUTCOME>> {

}
