package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ProductRankingDecoderFactory
        implements RankingExampleDecoderFactory<ProductQuery, Product, ProductOutcome> {
    @Override
    @SuppressWarnings("removal")
    public RankingExampleDecoder<ProductQuery, Product, ProductOutcome> apply(
            Optional<JsonNode> configuration
    ) {
        return raw -> {
            ProductExampleJson.Decoded decoded = ProductExampleJson.decode(raw);
            var request = OfflineRankingRequest.ofAvailableActions(
                    decoded.exampleId(),
                    decoded.query(),
                    decoded.actions(),
                    FeatureStoreResponseContainer.empty()
            );
            List<RankingOutcome<ProductOutcome, Product>> outcomes = new ArrayList<>();
            if (decoded.labeled()) {
                for (int i = 0; i < decoded.actions().size(); i++) {
                    var action = decoded.actions().get(i);
                    var decision = RankingDecision.builder(action.actionId(), i, action.action()).build();
                    outcomes.add(new RankingOutcome<>(decision, new ProductOutcome(decoded.clicked().get(i))));
                }
            }
            return List.of(new RankingExample<>(decoded.exampleId(), request, List.copyOf(outcomes)));
        };
    }
}
