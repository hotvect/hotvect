package com.hotvect.api.algodefinition.topk;

import com.hotvect.api.data.topk.AvailableAction;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.state.State;
import com.hotvect.api.transformation.memoization.Computing;

import java.util.List;

public interface AvailableActionState<SHARED, ACTION> extends State {
    List<AvailableAction<ACTION>> getAvailableActions(TopKRequest<SHARED, ACTION> topKRequest);
}
