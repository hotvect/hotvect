package com.hotvect.core.transform.topk;

import com.hotvect.core.transform.Computable;

public record AvailableAction<ACTION>(String actionId, Computable<ACTION> action) {}