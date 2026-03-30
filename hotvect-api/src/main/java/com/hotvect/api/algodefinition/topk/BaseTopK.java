package com.hotvect.api.algodefinition.topk;

import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.topk.AvailableAction;

/**
 * @deprecated Use com.hotvect.core.transform.topk.BaseTopK instead
 */
@Deprecated(forRemoval = true, since = "9.27.0")
public interface BaseTopK<SHARED, ACTION> extends TopK<SHARED, AvailableAction<ACTION>> {
}
