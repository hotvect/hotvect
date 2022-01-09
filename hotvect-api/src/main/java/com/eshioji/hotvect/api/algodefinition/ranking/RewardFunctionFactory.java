package com.eshioji.hotvect.api.algodefinition.ranking;

import java.util.function.Supplier;

public interface RewardFunctionFactory<OUTCOME> extends Supplier<RewardFunction<OUTCOME>> {

}
