package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.core.annotation.GenerateSimpleRankingTransformer;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

@GenerateSimpleRankingTransformer(
        name = "ProductFeatureTransformer",
        sharedType = ProductQuery.class,
        actionType = Product.class,
        features = ProductFeatures.class,
        backend = com.hotvect.catboost.CatBoostBackend.class,
        algorithmDefinitionResource = "example-product-scorer-algorithm-definition.json"
)
public final class ProductTransformerFactory implements RankingTransformerFactory<ProductQuery, Product> {
    @Override
    @SuppressWarnings("removal")
    public RankingTransformer<ProductQuery, Product> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters
    ) {
        return new ProductFeatureTransformer(request -> Map.of());
    }
}
