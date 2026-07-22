package com.hotvect.api.data.ranking;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingRequestTest {
    @Test
    void createsRequestFromAvailableActions() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(
                        AvailableAction.of("sku-a", "a", Map.of("source", "request")),
                        AvailableAction.of("sku-b", "b")
                )
        );

        assertEquals(List.of("a", "b"), request.availableActions());
        assertEquals("sku-a", request.actions().get(0).actionId());
        assertEquals("sku-b", request.actions().get(1).actionId());
        assertEquals(Map.of("source", "request"), request.actions().get(0).additionalProperties());
        assertEquals(Map.of(), request.additionalProperties());
    }

    @Test
    void createsRequestWithAdditionalProperties() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(AvailableAction.of("sku-a", "a")),
                Map.of("source", "request")
        );

        assertEquals(Map.of("source", "request"), request.additionalProperties());
    }

    @Test
    void legacyConstructorDoesNotCopyRawActionAdditionalProperties() {
        ActionWithProperties action = new ActionWithProperties(Map.of("source", "raw-action"));

        RankingRequest<String, ActionWithProperties> request = new RankingRequest<>(
                "example",
                "shared",
                List.of(action)
        );

        assertEquals(Map.of(), request.actions().get(0).additionalProperties());
        assertEquals(Map.of(), request.additionalProperties());
    }

    @Test
    void rejectsBlankExampleId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RankingRequest.ofAvailableActions(
                        " ",
                        "shared",
                        List.of(AvailableAction.of("sku-a", "a"))
                )
        );
    }

    @Test
    void legacyConstructorCreatesPositionalActionIds() {
        RankingRequest<String, String> request = new RankingRequest<>(
                "example",
                "shared",
                List.of("a", "b")
        );

        assertEquals(List.of("a", "b"), request.availableActions());
        assertEquals("0", request.actions().get(0).actionId());
        assertEquals("1", request.actions().get(1).actionId());
    }

    @Test
    void legacyOfflineFactoryCreatesPositionalActionIdsWithFeatureStoreResponses() {
        FeatureStoreResponseContainer featureStoreResponses = FeatureStoreResponseContainer.empty();

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.newOfflineRankingRequest(
                "example",
                "shared",
                List.of("a", "b"),
                featureStoreResponses
        );

        assertEquals(List.of("a", "b"), request.availableActions());
        assertEquals("0", request.actions().get(0).actionId());
        assertEquals("1", request.actions().get(1).actionId());
        assertEquals(featureStoreResponses, request.featureStoreResponseContainer());
    }

    @Test
    void offlineRequestPreservesAdditionalPropertiesAndFeatureStoreContainer() {
        FeatureStoreResponseContainer featureStoreResponses = FeatureStoreResponseContainer.empty();

        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(AvailableAction.of("sku-a", "a")),
                featureStoreResponses,
                Map.of("source", "request")
        );

        assertEquals(Map.of("source", "request"), request.additionalProperties());
        assertEquals(featureStoreResponses, request.featureStoreResponseContainer());
    }

    @Test
    void legacyOfflineFactoryAllowsNullRawActions() {
        OfflineRankingRequest<String, String> request = OfflineRankingRequest.newOfflineRankingRequest(
                "example",
                "shared",
                Arrays.asList(null, "b")
        );

        assertEquals(Arrays.asList(null, "b"), request.availableActions());
        assertEquals("0", request.actions().get(0).actionId());
        assertEquals("1", request.actions().get(1).actionId());
    }

    @Test
    void rejectsBlankActionIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AvailableAction.of(" ", "a")
        );
    }

    @Test
    void rejectsDuplicateActionIds() {
        assertTrue(assertionsEnabled());
        assertThrows(
                IllegalArgumentException.class,
                () -> RankingRequest.ofAvailableActions(
                        "example",
                        "shared",
                        List.of(
                                AvailableAction.of("sku", "a"),
                                AvailableAction.of("sku", "b")
                        )
                )
        );
    }

    @Test
    void offlineRequestPreservesActionIdsAndFeatureStoreContainer() {
        OfflineRankingRequest<String, String> request = OfflineRankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(AvailableAction.of("sku-a", "a"))
        );

        assertEquals("sku-a", request.actions().get(0).actionId());
        assertEquals(List.of("a"), request.availableActions());
    }

    @Test
    void legacyRankingExampleConstructorPreservesRequestAdditionalProperties() {
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                "example",
                "shared",
                List.of(AvailableAction.of("sku-a", "a")),
                Map.of("source", "request")
        );

        RankingExample<String, String, Double> example = new RankingExample<>(
                "example",
                request,
                List.of()
        );

        assertEquals(Map.of("source", "request"), example.request().additionalProperties());
    }

    private static boolean assertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    private record ActionWithProperties(Map<String, Object> additionalProperties) {
    }
}
