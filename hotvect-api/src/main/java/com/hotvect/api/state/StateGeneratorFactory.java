package com.hotvect.api.state;

import com.hotvect.api.algodefinition.AlgorithmDefinition;

public interface StateGeneratorFactory<S extends State> {
    StateGenerator<S> getGenerator(AlgorithmDefinition algorithmDefinition, ClassLoader classLoader);
    StateCodec<S> getCodec();
}
