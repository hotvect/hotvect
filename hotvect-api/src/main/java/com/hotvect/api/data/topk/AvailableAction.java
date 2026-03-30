package com.hotvect.api.data.topk;

import com.hotvect.api.transformation.Computing;

/**
 * @deprecated Use com.hotvect.core.transform.topk.AvailableAction instead
 */
@Deprecated(forRemoval = true, since = "9.27.0")
public record AvailableAction<ACTION>(String actionId, Computing<ACTION> action) {}
