package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamingRankingTransformerTest {
    @Test
    void transformRejectsStreamCardinalityMismatch() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example-id",
                "shared",
                List.of(
                        AvailableAction.of("action-id-1", "action-1"),
                        AvailableAction.of("action-id-2", "action-2")
                )
        );
        StreamingRankingTransformer<String, String> transformer = new StreamingRankingTransformer<>() {
            @Override
            public Stream<TransformedAction<String>> transformStream(RankingRequest<String, String> request) {
                return Stream.of(TransformedAction.of("action-1", new com.hotvect.api.data.common.NamespacedRecordImpl<>()));
            }

            @Override
            public PreparedBatchStream<String> prepareBatchStream(RankingRequest<String, String> request) {
                return new PreparedBatchStream<>(Stream.of(transformStream(request).toList()), Map.of());
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return java.util.Collections.emptySortedSet();
            }
        };

        assertEquals(
                "RankingTransformer returned 1 transformed actions for 2 actions",
                assertThrows(IllegalArgumentException.class, () -> transformer.transform(request)).getMessage()
        );
    }
}
