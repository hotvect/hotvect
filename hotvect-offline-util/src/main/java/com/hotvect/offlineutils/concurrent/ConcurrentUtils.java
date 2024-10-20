package com.hotvect.offlineutils.concurrent;

import java.util.Optional;

public class ConcurrentUtils {
    private ConcurrentUtils(){}

    public static int getThreadNumForCpuBoundTasks(Optional<Integer> specifiedThreadNum){
        if (specifiedThreadNum.map(x -> x > 0).orElse(false)){
            // If there is a specified input, and it's valid, use that
            return specifiedThreadNum.get();
        } else {
            // The specified input is invalid, or absent. Come up with a recommendation
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
    }

    public static int getQueueLength(int threadNum, Optional<Integer> specifiedQueueLength){
        if (specifiedQueueLength.map(x -> x > 0).orElse(false)){
            // If there is a specified input, and it's valid, use that
            return specifiedQueueLength.get();
        } else {
            return threadNum * 4;
        }
    }


    public static int getBatchSize(Optional<Integer> specifiedBatchSize){
        if (specifiedBatchSize.map(x -> x > 0).orElse(false)){
            // If there is a specified input, and it's valid, use that
            return specifiedBatchSize.get();
        } else {
            // The specified input is invalid, or absent. Come up with a recommendation
            return 500;
        }
    }

}
