package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKResponse;

import java.nio.ByteBuffer;
import java.util.function.Function;

public class ThemedTopKResultFormatter<SHARED, ACTION, OUTCOME> extends TopKResultFormatter<SHARED, ACTION, OUTCOME> {

    @Override
    protected void addCustomFields(
            ObjectNode root,
            TopKExample<SHARED, ACTION, OUTCOME> ex,
            TopKResponse<ACTION> topKResult) {
        ThemedTopKResponse<ACTION> themedResponse = (ThemedTopKResponse<ACTION>) topKResult;
        root.put("action_list_id", themedResponse.getActionListId());
        ObjectNode actionListMetadata = root.putObject("action_list_metadata");
        themedResponse.getActionListMetadata().forEach(actionListMetadata::put);
    }

    @Override
    public Function<TopKExample<SHARED, ACTION, OUTCOME>, ByteBuffer> apply(RewardFunction<OUTCOME> rewardFunction, TopK<SHARED, ACTION> topK) {
        return ex -> {
            TopKResponse<ACTION> topKResult = topK.apply(ex.request());
            if (!(topKResult instanceof ThemedTopKResponse)) {
                throw new IllegalArgumentException("TopK must return a ThemedTopKResponse");
            }
            ObjectNode root = createResultNode(ex, topKResult, rewardFunction);
            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(root);
                byte[] withNewline = new byte[jsonBytes.length + 1];
                System.arraycopy(jsonBytes, 0, withNewline, 0, jsonBytes.length);
                withNewline[jsonBytes.length] = '\n';
                return ByteBuffer.wrap(withNewline);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }
}