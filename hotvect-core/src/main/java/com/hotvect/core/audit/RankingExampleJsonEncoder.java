package com.hotvect.core.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

public class RankingExampleJsonEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final RankingTransformer<SHARED, ACTION> rankingTransformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public RankingExampleJsonEncoder(RankingTransformer<SHARED, ACTION> rankingTransformer, RewardFunction<OUTCOME> rewardFunction) {
        this.rankingTransformer = rankingTransformer;
        this.rewardFunction = rewardFunction;
    }

    @Override
    public String encodedFileExtension() {
        return ".jsonl";
    }

    @Override
    public ByteBuffer apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        var root = objectMapper.createObjectNode();
        root.put("example_id", toEncode.exampleId());

        ArrayNode actionToEncoded = objectMapper.createArrayNode();
        var actions = toEncode.request().actions();
        var transformeds = rankingTransformer.transform(toEncode.request());
        var outcomes = toEncode.outcomes();
        checkArgument(
                transformeds.size() == actions.size(),
                "RankingTransformer returned %s transformed actions for %s actions",
                transformeds.size(),
                actions.size()
        );
        checkArgument(
                outcomes.size() == actions.size(),
                "RankingExample has %s outcomes for %s actions",
                outcomes.size(),
                actions.size()
        );

        for (int i = 0; i < actions.size(); i++) {
            var result = objectMapper.createObjectNode();
            String actionId = actions.get(i).actionId();
            var transformed = transformeds.get(i);
            var outcome = outcomes.get(i);
            checkArgument(
                    transformed.actionId().equals(actionId),
                    "RankingTransformer returned transformed action id %s at position %s, expected %s",
                    transformed.actionId(),
                    i,
                    actionId
            );
            checkArgument(
                    outcome.rankingDecision().actionId().equals(actionId),
                    "RankingExample outcome action id %s at position %s, expected %s",
                    outcome.rankingDecision().actionId(),
                    i,
                    actionId
            );
            result.put("action_id", actionId);
            result.put("reward", rewardFunction.applyAsDouble(outcome.outcome()));

            throw new UnsupportedOperationException("not yet supported");
//            Map<String, Object> pojonized = DataRecords.pojonize(transformed);
//            JsonNode features = objectMapper.valueToTree(pojonized);
//
//            result.set("features", features);
//            actionToEncoded.add(result);
        }

        root.set("actions", actionToEncoded);

        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(root);
            byte[] bytesWithNewline = new byte[jsonBytes.length + 1];
            System.arraycopy(jsonBytes, 0, bytesWithNewline, 0, jsonBytes.length);
            bytesWithNewline[jsonBytes.length] = '\n';
            return ByteBuffer.wrap(bytesWithNewline);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
