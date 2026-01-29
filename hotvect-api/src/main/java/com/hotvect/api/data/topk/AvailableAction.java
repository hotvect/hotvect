package com.hotvect.api.data.topk;

import com.hotvect.api.transformation.Computing;

public record AvailableAction<ACTION>(String actionId, Computing<ACTION> action) {}
