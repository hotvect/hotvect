package com.hotvect.api.data;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FeatureStoreResponseContainerTest {

    @Test
    void constructor_invalidArguments() {
        assertThatThrownBy(() -> new FeatureStoreResponseContainer(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("featureStoreResponses cannot be null");
    }

}
