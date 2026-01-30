package com.hotvect.api.algodefinition.state;

import com.hotvect.api.algodefinition.AlgorithmDefinition;

public interface StateGeneratorFactory {
    StateGenerator getGenerator(AlgorithmDefinition algorithmDefinition, ClassLoader classLoader);
}
