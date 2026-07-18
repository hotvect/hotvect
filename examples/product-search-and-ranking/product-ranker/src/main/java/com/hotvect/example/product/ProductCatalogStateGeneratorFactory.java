package com.hotvect.example.product;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.state.StateGenerator;
import com.hotvect.api.algodefinition.state.StateGeneratorFactory;

public final class ProductCatalogStateGeneratorFactory implements StateGeneratorFactory {
    @Override
    public StateGenerator getGenerator(AlgorithmDefinition algorithmDefinition, ClassLoader classLoader) {
        return new ProductCatalogStateGenerator();
    }
}
