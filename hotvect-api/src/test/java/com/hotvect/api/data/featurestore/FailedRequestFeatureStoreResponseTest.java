package com.hotvect.api.data.featurestore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FailedRequestFeatureStoreResponseTest {
    @Test
    void onlyRequestFailureIsSet() {
        String failure = "network error";
        FailedRequestFeatureStoreResponse resp = new FailedRequestFeatureStoreResponse(failure);
        assertThat(resp.getRequestFailure()).contains(failure);
        assertThat(resp.getAllEntities()).isEmpty();
    }
}
