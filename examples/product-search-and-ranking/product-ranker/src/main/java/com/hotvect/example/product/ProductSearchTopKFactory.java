package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.topk.CompositeTopKFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProductSearchTopKFactory implements CompositeTopKFactory<ProductQuery, Product> {
    static final String CATALOG_DEPENDENCY = "example-product-search-index";
    static final String RANKER_DEPENDENCY = "example-product-ranker";

    @Override
    @SuppressWarnings({"unchecked", "removal"})
    public TopK<ProductQuery, Product> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> dependencies
    ) {
        ProductCatalogState catalog = (ProductCatalogState) dependencies.get(CATALOG_DEPENDENCY).algorithm();
        Ranker<ProductQuery, Product> ranker = (Ranker<ProductQuery, Product>) dependencies
                .get(RANKER_DEPENDENCY)
                .algorithm();

        return request -> {
            int retrievalLimit = Math.min(catalog.size(), Math.max(request.k(), 8));
            List<AvailableAction<Product>> candidates = catalog.retrieve(request.shared(), retrievalLimit);
            RankingResponse<Product> rankingResponse = ranker.rank(toRankingRequest(request, candidates));
            int resultSize = Math.min(request.k(), rankingResponse.decisions().size());
            List<TopKDecision<Product>> decisions = new ArrayList<>(resultSize);
            for (RankingDecision<Product> product : rankingResponse.decisions().subList(0, resultSize)) {
                decisions.add(TopKDecision.builder(product.actionId(), product.action())
                        .withScore(product.score())
                        .withProbability(product.probability())
                        .withAdditionalProperties(product.additionalProperties())
                        .build());
            }

            Map<String, Object> metadata = new LinkedHashMap<>(rankingResponse.additionalProperties());
            metadata.put("catalog_size", catalog.size());
            metadata.put("retrieval_policy", ProductCatalogState.RETRIEVAL_POLICY);
            metadata.put("retrieved_candidates", candidates.size());
            return TopKResponse.newResponse(
                    List.copyOf(decisions),
                    rankingResponse.featureStoreResponseContainer(),
                    Map.copyOf(metadata)
            );
        };
    }

    private static RankingRequest<ProductQuery, Product> toRankingRequest(
            TopKRequest<ProductQuery> request,
            List<AvailableAction<Product>> candidates
    ) {
        if (request instanceof OfflineRequest<?> offlineRequest) {
            return OfflineRankingRequest.ofAvailableActions(
                    request.exampleId(),
                    request.shared(),
                    candidates,
                    offlineRequest.featureStoreResponseContainer()
            );
        }
        return RankingRequest.ofAvailableActions(request.exampleId(), request.shared(), candidates);
    }
}
