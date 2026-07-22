package com.hotvect.example.product;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;

public final class ProductRewardFunctionFactory implements RewardFunctionFactory<ProductOutcome> {
    @Override
    public RewardFunction<ProductOutcome> get() {
        return outcome -> outcome.clicked() ? 1.0 : 0.0;
    }
}
