package com.hotvect.offlineutils.export;

import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.topk.OfflineTopKRequest;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKOutcome;
import com.hotvect.api.data.topk.TopKResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopKResultFormatterTest {

    @Test
    void given_missing_outcome_omits_reward() {
        var formatter = new TopKResultFormatter<Void, String, Double>()
                .apply(x -> x, constantTopKResponse());

        var actual = formatter.apply(
                new TopKExample<>(
                        "example_1",
                        OfflineTopKRequest.newOfflineTopKRequest("example_1", null, null, 3),
                        List.of(
                                new TopKOutcome<>(
                                        TopKDecision.builder("a", "A").withScore(0.9).build(),
                                        1.0
                                ),
                                new TopKOutcome<>(
                                        TopKDecision.builder("c", "C").withScore(0.7).build(),
                                        3.0
                                )
                        )
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"a\",\"rank\":0,\"score\":0.9,\"reward\":1.0},{\"action_id\":\"b\",\"rank\":1,\"score\":0.8},{\"action_id\":\"c\",\"rank\":2,\"score\":0.7,\"reward\":3.0}]}\n",
                new String(actual.array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void themed_formatter_keeps_missing_reward_absent() {
        var formatter = new ThemedTopKResultFormatter<Void, String, Double>()
                .apply(x -> x, themedTopKResponse());

        var actual = formatter.apply(
                new TopKExample<>(
                        "example_1",
                        OfflineTopKRequest.newOfflineTopKRequest("example_1", null, null, 2),
                        List.of(
                                new TopKOutcome<>(
                                        TopKDecision.builder("a", "A").withScore(0.9).build(),
                                        1.0
                                )
                        )
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"action_list_id\":\"theme_1\",\"action_list_metadata\":{\"slot\":\"hero\"},\"result\":[{\"action_id\":\"a\",\"rank\":0,\"score\":0.9,\"reward\":1.0},{\"action_id\":\"b\",\"rank\":1,\"score\":0.8}]}\n",
                new String(actual.array(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void given_null_outcome_omits_reward() {
        var formatter = new TopKResultFormatter<Void, String, Double>()
                .apply(x -> {
                    throw new AssertionError("rewardFunction should not be called for null outcomes");
                }, constantTopKResponse());

        var actual = formatter.apply(
                new TopKExample<>(
                        "example_1",
                        OfflineTopKRequest.newOfflineTopKRequest("example_1", null, null, 3),
                        List.of(
                                new TopKOutcome<>(
                                        TopKDecision.builder("a", "A").withScore(0.9).build(),
                                        null
                                )
                        )
                )
        );

        assertEquals(
                "{\"example_id\":\"example_1\",\"result\":[{\"action_id\":\"a\",\"rank\":0,\"score\":0.9},{\"action_id\":\"b\",\"rank\":1,\"score\":0.8},{\"action_id\":\"c\",\"rank\":2,\"score\":0.7}]}\n",
                new String(actual.array(), StandardCharsets.UTF_8)
        );
    }

    private static TopK<Void, String> constantTopKResponse() {
        return request -> TopKResponse.newResponse(List.of(
                TopKDecision.builder("a", "A").withScore(0.9).build(),
                TopKDecision.builder("b", "B").withScore(0.8).build(),
                TopKDecision.builder("c", "C").withScore(0.7).build()
        ));
    }

    private static TopK<Void, String> themedTopKResponse() {
        return request -> ThemedTopKResponse.newResponse(
                "theme_1",
                List.of(
                        TopKDecision.builder("a", "A").withScore(0.9).build(),
                        TopKDecision.builder("b", "B").withScore(0.8).build()
                ),
                Map.of("slot", "hero")
        );
    }
}
