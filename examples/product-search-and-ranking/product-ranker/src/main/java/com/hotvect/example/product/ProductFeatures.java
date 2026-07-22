package com.hotvect.example.product;

import com.hotvect.core.annotation.Feature;
import com.hotvect.core.annotation.Inject;
import com.hotvect.core.annotation.SharedFeature;

import java.util.Set;

public final class ProductFeatures {
    private ProductFeatures() {
    }

    @SharedFeature("normalized_query")
    public static String normalizedQuery(ProductQuery query) {
        return ProductText.normalize(query.query());
    }

    @SharedFeature("normalized_preferred_category")
    public static String normalizedPreferredCategory(ProductQuery query) {
        return ProductText.normalize(query.preferredCategory());
    }

    @SharedFeature("budget")
    public static double budget(ProductQuery query) {
        return query.budget();
    }

    @Feature("candidate_category")
    public static String candidateCategory(Product product) {
        return ProductText.normalize(product.category());
    }

    @Feature("query_title_overlap")
    public static double queryTitleOverlap(
            @Inject("normalized_query") String normalizedQuery,
            Product product
    ) {
        Set<String> queryTokens = ProductText.tokens(normalizedQuery);
        Set<String> titleTokens = ProductText.tokens(product.title());
        long matches = queryTokens.stream().filter(titleTokens::contains).count();
        return (double) matches / queryTokens.size();
    }

    @Feature("preferred_category_match")
    public static double preferredCategoryMatch(
            @Inject("normalized_preferred_category") String preferredCategory,
            Product product
    ) {
        return preferredCategory.equals(ProductText.normalize(product.category())) ? 1.0 : 0.0;
    }

    @Feature("budget_fit")
    public static double budgetFit(@Inject("budget") double budget, Product product) {
        return product.price() <= budget ? 1.0 : 0.0;
    }

    @Feature("popularity")
    public static double popularity(Product product) {
        return product.popularity();
    }

    @Feature("novelty")
    public static double novelty(Product product) {
        return product.novelty();
    }

}
