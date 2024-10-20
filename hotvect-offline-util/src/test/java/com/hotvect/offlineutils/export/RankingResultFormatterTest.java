package com.hotvect.offlineutils.export;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankingResultFormatterTest {
    @Test
    void given_ranking_example_format_while_preserving_original_index() throws Exception {
        var testSubject = new RankingResultFormatter<Void, String, Double>();

        Ranker<Void, String> reverseRanker = new Ranker<>() {
            @Override
            public RankingResponse<String> rank(RankingRequest<Void, String> rankingRequest) {
                List<RankingDecision<String>> ret = new ArrayList<>();

                // This ranker ranks the input in reverse order so that we can test how the result formatter
                // restores the original order
                for (int i = rankingRequest.getAvailableActions().size() - 1; i >= 0; i--) {
                    RankingDecision<String> decision = RankingDecision.builder(i, rankingRequest.getAvailableActions().get(i)).build();
                    ret.add(decision);
                }
                return RankingResponse.newResponse(ret, ImmutableMap.of("log", "me"));
            }
        };

        var formatter = testSubject.apply(d -> d, reverseRanker);
        var actual = formatter.apply(
                new RankingExample<>(
                        "example_1",
                        new RankingRequest<>(
                                "example_1",
                                null,
                                ImmutableList.of("a", "b", "c")
                        ),
                        ImmutableList.of(
                                new RankingOutcome<>(
                                        RankingDecision.builder(0,"a").build(),
                                        1.0
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder(1,"b").build(),
                                        2.0
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder(2,"c").build(),
                                        3.0
                                )
                        )
                )
        );

        assertEquals("{\"example_id\":\"example_1\",\"additional_properties\":{\"log\":\"me\"},\"result\":[{\"rank\":2,\"reward\":1.0},{\"rank\":1,\"reward\":2.0},{\"rank\":0,\"reward\":3.0}]}", actual);

    }

    private static class ExampleOutcome {
        final double outcome;

        private ExampleOutcome(double outcome) {
            this.outcome = outcome;
        }

        public Map<String, Object> getAdditionalProperties(){
            return ImmutableMap.of("outcome", outcome);
        }
    }
    @Test
    void given_ranking_example_format_while_preserving_original_index_with_additional_properties() throws Exception {
        var testSubject = new RankingResultFormatter<Void, String, ExampleOutcome>();

        Ranker<Void, String> reverseRanker = new Ranker<>() {
            @Override
            public RankingResponse<String> rank(RankingRequest<Void, String> rankingRequest) {
                List<RankingDecision<String>> ret = new ArrayList<>();

                // This ranker ranks the input in reverse order so that we can test how the result formatter
                // restores the original order
                for (int i = rankingRequest.getAvailableActions().size() - 1; i >= 0; i--) {
                    RankingDecision<String> decision = RankingDecision.builder(i, rankingRequest.getAvailableActions().get(i)).build();
                    ret.add(decision);
                }
                return RankingResponse.newResponse(ret);
            }
        };

        var formatter = testSubject.apply(d -> d.outcome, reverseRanker);
        var actual = formatter.apply(
                new RankingExample<>(
                        "example_1",
                        new RankingRequest<>(
                                "example_1",
                                null,
                                ImmutableList.of("a", "b", "c")
                        ),
                        ImmutableList.of(
                                new RankingOutcome<>(
                                        RankingDecision.builder(0,"a").build(),
                                        new ExampleOutcome(1.0)
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder(1,"b").build(),
                                        new ExampleOutcome(2.0)
                                ),
                                new RankingOutcome<>(
                                        RankingDecision.builder(2,"c").build(),
                                        new ExampleOutcome(3.0)
                                )
                        )
                )
        );

        assertEquals("{\"example_id\":\"example_1\",\"result\":[{\"rank\":2,\"reward\":1.0,\"additional_properties\":{\"outcome\":1.0}},{\"rank\":1,\"reward\":2.0,\"additional_properties\":{\"outcome\":2.0}},{\"rank\":0,\"reward\":3.0,\"additional_properties\":{\"outcome\":3.0}}]}", actual);

    }

}