package com.hotvect.api.data.topk;

import com.hotvect.api.data.common.Example;

import java.util.List;

public record TopKExample<SHARED, ACTION, OUTCOME>(
        String exampleId,
        OfflineTopKRequest<SHARED> request,
        List<TopKOutcome<OUTCOME, ACTION>> outcomes
) implements Example<OfflineTopKRequest<SHARED>, TopKOutcome<OUTCOME, ACTION>> {

}
