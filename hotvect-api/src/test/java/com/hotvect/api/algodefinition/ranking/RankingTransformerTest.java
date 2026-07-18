package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankingTransformerTest {
    private record TestNamespace(String name) implements Namespace {
        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void defaultTransformPairsActionsWithReturnedRecords() {
        RankingTransformer<String, String> transformer = transformerReturning(2);
        RankingRequest<String, String> request = request("a", "b");
        List<TransformedAction<String>> transformedActions = transformer.transform(request);

        assertEquals(2, transformedActions.size());
        assertEquals("a", transformedActions.get(0).actionId());
        assertEquals("a", transformedActions.get(0).action());
        assertEquals("b", transformedActions.get(1).actionId());
        assertEquals("b", transformedActions.get(1).action());
    }

    @Test
    void defaultTransformPropagatesAvailableActionAdditionalProperties() {
        RankingTransformer<String, String> transformer = transformerReturning(1);
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                List.of(AvailableAction.of("a", "a", Map.of("source", "request")))
        );

        List<TransformedAction<String>> transformedActions = transformer.transform(request);

        assertEquals(Map.of("source", "request"), transformedActions.get(0).additionalProperties());
    }

    private RankingRequest<String, String> request(String... actions) {
        return RankingRequest.ofAvailableActions(
                "example_1",
                "shared",
                List.of(actions).stream()
                        .map(action -> AvailableAction.of(action, action))
                        .toList()
        );
    }

    private RankingTransformer<String, String> transformerReturning(int count) {
        Namespace feature = new TestNamespace("f_1");
        return new RankingTransformer<>() {
            @Override
            public List<NamespacedRecord<Namespace, Object>> apply(RankingRequest<String, String> rankingRequest) {
                return java.util.stream.IntStream.range(0, count)
                        .mapToObj(i -> record(feature))
                        .toList();
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                TreeSet<Namespace> ret = new TreeSet<>(Namespace.alphabetical());
                ret.add(feature);
                return ret;
            }
        };
    }

    private NamespacedRecord<Namespace, Object> record(Namespace feature) {
        NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
        record.put(feature, 123);
        return record;
    }
}
