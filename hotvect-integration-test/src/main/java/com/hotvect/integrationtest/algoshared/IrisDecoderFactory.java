package com.hotvect.integrationtest.algoshared;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.RankingRequest;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public class IrisDecoderFactory implements RankingExampleDecoderFactory<String, Map<String, String>, String> {
    @Override
    public RankingExampleDecoder<String, Map<String, String>, String> apply(Optional<JsonNode> hyperparameter) {
        return toDecode -> {
            Properties props = new Properties();
            try {
                props.load(new StringReader(toDecode));
                Map<String, String> record = Maps.fromProperties(props);
                String shared = record.get("shared");
                String outcome = record.get("outcome");
                record.remove("shared");
                record.remove("outcome");

                RankingExample<String, Map<String, String>, String> exp = new RankingExample<>(
                        "incoming:" +toDecode,
                        new RankingRequest<>(
                                "example1",
                                shared,
                                ImmutableList.of(
                                        record
                                )),
                        ImmutableList.of(
                                new RankingOutcome<>(RankingDecision.builder(0, record).withScore(1.0).build(), outcome)
                        )
                );
                return ImmutableList.of(exp);
            } catch (IOException e) {
                throw new AssertionError("not implemented");
            }
        };
    }
}

