package com.hotvect.integrationtest.iris;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;

public class IrisRewardFunctionFactory implements RewardFunctionFactory<String> {
    @Override
    public RewardFunction<String> get() {
        return s -> {
            if(s.equals("setosa")){
                return 2.0;
            } else {
                return 0.0;
            }
        };
    }
}
