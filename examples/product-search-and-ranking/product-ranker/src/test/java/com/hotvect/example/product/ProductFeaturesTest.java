package com.hotvect.example.product;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductFeaturesTest {
    @Test
    void computesQueryCategoryAndBudgetSignals() {
        ProductQuery query = new ProductQuery("Blue excavator", "Vehicles", 30.0);
        Product matching = new Product("Blue toy excavator", "vehicles", 25.9, 0.82, 0.41);
        Product other = new Product("Wooden tea party set", "pretend-play", 31.5, 0.75, 0.63);

        String normalizedQuery = ProductFeatures.normalizedQuery(query);
        String preferredCategory = ProductFeatures.normalizedPreferredCategory(query);

        assertEquals("blue excavator", normalizedQuery);
        assertEquals(1.0, ProductFeatures.queryTitleOverlap(normalizedQuery, matching));
        assertEquals(0.0, ProductFeatures.queryTitleOverlap(normalizedQuery, other));
        assertEquals(1.0, ProductFeatures.preferredCategoryMatch(preferredCategory, matching));
        assertEquals(0.0, ProductFeatures.preferredCategoryMatch(preferredCategory, other));
        assertEquals(1.0, ProductFeatures.budgetFit(ProductFeatures.budget(query), matching));
        assertEquals(0.0, ProductFeatures.budgetFit(ProductFeatures.budget(query), other));
    }

    @Test
    void matchesSimpleWordFormsUsedBySearchQueries() {
        Product product = new Product("Rainbow pebble stacking set", "stacking", 10.0, 0.5, 0.5);

        assertEquals(1.0, ProductFeatures.queryTitleOverlap("rainbow stacker", product));
    }
}
