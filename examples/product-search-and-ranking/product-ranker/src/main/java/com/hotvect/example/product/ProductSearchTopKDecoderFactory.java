package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.topk.TopKExampleDecoderFactory;
import com.hotvect.api.codec.topk.TopKExampleDecoder;
import com.hotvect.api.data.topk.OfflineTopKRequest;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKOutcome;

import java.util.List;
import java.util.Optional;

public final class ProductSearchTopKDecoderFactory
        implements TopKExampleDecoderFactory<ProductQuery, Product, ProductOutcome> {
    @Override
    @SuppressWarnings("removal")
    public TopKExampleDecoder<ProductQuery, Product, ProductOutcome> apply(Optional<JsonNode> configuration) {
        return raw -> {
            ProductSearchExampleJson.Decoded decoded = ProductSearchExampleJson.decode(raw);
            var request = OfflineTopKRequest.newOfflineTopKRequest(
                    decoded.exampleId(),
                    decoded.occurredAt(),
                    decoded.query(),
                    decoded.k()
            );
            List<TopKOutcome<ProductOutcome, Product>> outcomes = decoded.outcomes().stream()
                    .map(outcome -> new TopKOutcome<>(
                            TopKDecision.<Product>builder(outcome.actionId(), null).build(),
                            new ProductOutcome(outcome.clicked())
                    ))
                    .toList();
            return List.of(new TopKExample<>(decoded.exampleId(), request, outcomes));
        };
    }
}
