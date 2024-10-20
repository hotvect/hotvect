package com.hotvect.core.transform.ranking;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

public class TestShared {
    private final Map<String, Double> shared = ImmutableMap.of("ABC", 1.0);

    public Map<String, Double> getData(){
        return shared;
    }
}
