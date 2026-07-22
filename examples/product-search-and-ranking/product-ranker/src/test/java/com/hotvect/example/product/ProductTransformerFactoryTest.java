package com.hotvect.example.product;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductTransformerFactoryTest {
    @Test
    @SuppressWarnings("removal")
    void generatedTransformerPreservesIdsAndEmitsDeclaredFeatures() {
        ProductQuery query = new ProductQuery("Blue excavator", "vehicles", 30.0);
        Product excavator = new Product("Blue toy excavator", "vehicles", 25.9, 0.82, 0.41);
        Product teaSet = new Product("Wooden tea party set", "pretend-play", 31.5, 0.75, 0.63);
        var request = RankingRequest.ofAvailableActions(
                "feature-example",
                query,
                List.of(
                        AvailableAction.of("toy-excavator", excavator),
                        AvailableAction.of("wooden-tea-set", teaSet)
                )
        );

        var transformer = new ProductTransformerFactory().apply(Optional.empty(), Map.of());
        var transformed = transformer.transform(request);

        assertEquals(List.of("toy-excavator", "wooden-tea-set"), transformed.stream().map(item -> item.actionId()).toList());
        Map<String, Object> firstFeatures = namedFeatures(transformed.getFirst().transformed().asMap());
        assertEquals("vehicles", firstFeatures.get("candidate_category"));
        assertEquals(1.0, firstFeatures.get("query_title_overlap"));
        assertEquals(1.0, firstFeatures.get("preferred_category_match"));
        assertEquals(1.0, firstFeatures.get("budget_fit"));
        assertEquals(0.82, firstFeatures.get("popularity"));
        assertEquals(0.41, firstFeatures.get("novelty"));
        assertEquals(
                List.of(
                        "budget_fit",
                        "candidate_category",
                        "novelty",
                        "popularity",
                        "preferred_category_match",
                        "query_title_overlap"
                ),
                transformer.getUsedFeatures().stream().map(Object::toString).toList()
        );
    }

    private static Map<String, Object> namedFeatures(Map<?, Object> raw) {
        Map<String, Object> named = new LinkedHashMap<>();
        raw.forEach((namespace, value) -> named.put(namespace.toString(), value));
        return named;
    }
}
