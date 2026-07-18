package com.hotvect.example.product;

import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.data.AvailableAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProductCatalogState implements Algorithm {
    static final String RETRIEVAL_POLICY = "lexical-title-category-v1";

    private final List<AvailableAction<Product>> products;

    public ProductCatalogState(List<AvailableAction<Product>> products) {
        if (products.isEmpty()) {
            throw new IllegalArgumentException("products cannot be empty");
        }
        Set<String> actionIds = new HashSet<>();
        for (AvailableAction<Product> product : products) {
            if (!actionIds.add(product.actionId())) {
                throw new IllegalArgumentException("Duplicate catalog action_id: " + product.actionId());
            }
        }
        this.products = List.copyOf(products);
    }

    public int size() {
        return products.size();
    }

    public List<AvailableAction<Product>> retrieve(ProductQuery query, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Set<String> queryTokens = ProductText.tokens(query.query());
        List<ScoredProduct> scored = products.stream()
                .map(product -> new ScoredProduct(product, retrievalScore(query, queryTokens, product.action())))
                .sorted(Comparator
                        .comparingDouble(ScoredProduct::score)
                        .reversed()
                        .thenComparing(scoredProduct -> scoredProduct.product().actionId()))
                .limit(limit)
                .toList();

        List<AvailableAction<Product>> retrieved = new ArrayList<>(scored.size());
        for (int index = 0; index < scored.size(); index++) {
            ScoredProduct scoredProduct = scored.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>(scoredProduct.product().additionalProperties());
            metadata.put("retrieval_policy", RETRIEVAL_POLICY);
            metadata.put("retrieval_rank", index + 1);
            metadata.put("retrieval_score", scoredProduct.score());
            retrieved.add(AvailableAction.of(
                    scoredProduct.product().actionId(),
                    scoredProduct.product().action(),
                    Map.copyOf(metadata)
            ));
        }
        return List.copyOf(retrieved);
    }

    private static double retrievalScore(ProductQuery query, Set<String> queryTokens, Product product) {
        long titleMatches = queryTokens.stream().filter(ProductText.tokens(product.title())::contains).count();
        long categoryMatches = queryTokens.stream().filter(ProductText.tokens(product.category())::contains).count();
        double preferredCategory = product.category().equalsIgnoreCase(query.preferredCategory()) ? 1.0 : 0.0;
        return titleMatches * 3.0
                + categoryMatches * 2.0
                + preferredCategory
                + product.popularity() * 0.01;
    }

    private record ScoredProduct(AvailableAction<Product> product, double score) {
    }
}
