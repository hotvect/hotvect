package com.hotvect.api.data.featurestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;

class SimpleFeatureStoreResponseTest {

    // ==================== Factory Method: success ====================

    @Test
    void success_withEntities() {
        Map<String, Object> entityId = Map.of("id", "1");
        Map<String, Object> features = Map.of("feature1", "value1");
        var response = SimpleFeatureStoreResponse.success(Map.of(entityId, features));

        assertThat(response.getRequestFailure()).isEmpty();
        assertThat(response.getAllEntities()).hasSize(1);
        assertThat(response.getEntity(entityId)).isEqualTo(features);
    }

    @ParameterizedTest(name = "When entities is {0}")
    @NullAndEmptySource
    void success_whenEntitiesIsEmptyOrNull(Map<Map<String, Object>, Map<String, Object>> entities) {
        var response = SimpleFeatureStoreResponse.success(entities);

        assertThat(response.getRequestFailure()).isEmpty();
        assertThat(response.getAllEntities()).isEmpty();
    }

    // ==================== Factory Method: failure ====================

    @Test
    void failure_withValidMessage() {
        var response = SimpleFeatureStoreResponse.failure("Error message");

        assertThat(response.getRequestFailure()).contains("Error message");
        assertThat(response.getAllEntities()).isEmpty();
    }

    @ParameterizedTest(name = "When failureMessage is ''{0}''")
    @NullSource
    @MethodSource("com.hotvect.testutils.TestUtils#blankStrings")
    void failure_whenFailureMessageIsInvalid(String failureMessage) {
        assertThatThrownBy(() -> SimpleFeatureStoreResponse.failure(failureMessage))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureMessage must be non-null and non-blank");
    }

    // ==================== Factory Method: partial ====================

    @Test
    void partial_withEntitiesAndFailure() {
        Map<String, Object> entityId = Map.of("id", "1");
        Map<String, Object> features = Map.of("feature1", "value1");
        var response = SimpleFeatureStoreResponse.partial(
            Map.of(entityId, features),
            "Partial failure"
        );

        assertThat(response.getRequestFailure()).contains("Partial failure");
        assertThat(response.getAllEntities()).hasSize(1);
        assertThat(response.getEntity(entityId)).isEqualTo(features);
    }

    @ParameterizedTest(name = "When entities is {0}")
    @NullAndEmptySource
    void partial_whenEntitiesIsEmptyOrNull(Map<Map<String, Object>, Map<String, Object>> entities) {
        var response = SimpleFeatureStoreResponse.partial(entities, "Partial failure");

        assertThat(response.getRequestFailure()).contains("Partial failure");
        assertThat(response.getAllEntities()).isEmpty();
    }

    @ParameterizedTest(name = "When failureMessage is ''{0}''")
    @NullSource
    @MethodSource("com.hotvect.testutils.TestUtils#blankStrings")
    void partial_whenFailureMessageIsInvalid(String failureMessage) {
        assertThatThrownBy(() -> SimpleFeatureStoreResponse.partial(Map.of(), failureMessage))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureMessage must be non-null and non-blank");
    }

    // ==================== Immutability Tests ====================

    @Test
    void getAllEntities_returnsUnmodifiableMap() {
        Map<String, Object> entityId = Map.of("id", "1");
        var response = SimpleFeatureStoreResponse.success(Map.of(entityId, Map.of("feature1", "value1")));

        assertThatThrownBy(() -> response.getAllEntities().put(Map.of("id", "2"), Map.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================== getEntity() Tests ====================

    @Test
    void getEntity_whenEntityExists() {
        Map<String, Object> entityId = Map.of("id", "1");
        Map<String, Object> features = Map.of("feature1", "value1");
        var response = SimpleFeatureStoreResponse.success(Map.of(entityId, features));

        assertThat(response.getEntity(entityId)).isEqualTo(features);
    }

    @Test
    void getEntity_whenEntityDoesNotExist() {
        Map<String, Object> entityId = Map.of("id", "1");
        Map<String, Object> nonExistentId = Map.of("id", "2");
        var response = SimpleFeatureStoreResponse.success(Map.of(entityId, Map.of("feature1", "value1")));

        assertThat(response.getEntity(nonExistentId)).isNull();
    }

    @Test
    void getEntity_whenResponseIsEmpty() {
        Map<String, Object> entityId = Map.of("id", "1");
        var response = SimpleFeatureStoreResponse.success(Map.of());

        assertThat(response.getEntity(entityId)).isNull();
    }

    // ==================== Builder Tests ====================

    @Test
    @SuppressWarnings("deprecation")
    void builder_withOnlyEntities() {
        Map<String, Object> entityId = Map.of("id", "1");
        Map<String, Object> features = Map.of("feature1", "value1");
        var response = SimpleFeatureStoreResponse.builder()
            .allEntities(Map.of(entityId, features))
            .build();

        assertThat(response.getRequestFailure()).isEmpty();
        assertThat(response.getAllEntities()).hasSize(1);
        assertThat(response.getEntity(entityId)).isEqualTo(features);
    }

    @Test
    @SuppressWarnings("deprecation")
    void builder_withOnlyFailure() {
        var response = SimpleFeatureStoreResponse.builder()
            .requestFailure("Error occurred")
            .build();

        assertThat(response.getRequestFailure()).contains("Error occurred");
        assertThat(response.getAllEntities()).isEmpty();
    }

    @Test
    @SuppressWarnings("deprecation")
    void builder_withNoFieldsSet() {
        var response = SimpleFeatureStoreResponse.builder()
            .build();

        assertThat(response.getRequestFailure()).isEmpty();
        assertThat(response.getAllEntities()).isEmpty();
    }

    @Test
    @SuppressWarnings("deprecation")
    void builder_withEntitiesAndFailure_throwsIllegalStateException() {
        Map<String, Object> entityId = Map.of("id", "1");
        Map<String, Object> features = Map.of("feature1", "value1");

        assertThatThrownBy(() -> SimpleFeatureStoreResponse.builder()
            .allEntities(Map.of(entityId, features))
            .requestFailure("Error occurred")
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot have entity/feature data or errors when a request-level failure is present");
    }

}
