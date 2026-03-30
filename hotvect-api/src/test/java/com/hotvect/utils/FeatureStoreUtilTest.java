package com.hotvect.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.featurestore.FailedRequestFeatureStoreResponse;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeatureStoreUtilTest {

    // ==================== deepMerge() Container tests ====================

    @Test
    void deepMergeContainer_differentViewNames() {
        var response1 = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );
        var response2 = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "2"), Map.of("feature2", "value2"))
        );
        var first = new FeatureStoreResponseContainer(ImmutableMap.of("view1_v1", response1));
        var second = new FeatureStoreResponseContainer(ImmutableMap.of("view2_v1", response2));

        var result = FeatureStoreUtil.deepMerge(first, second);

        assertThat(result.featureStoreResponses()).hasSize(2);
        assertThat(result.featureStoreResponses().get("view1_v1").getAllEntities()).isEqualTo(response1.getAllEntities());
        assertThat(result.featureStoreResponses().get("view2_v1").getAllEntities()).isEqualTo(response2.getAllEntities());
    }

    @Test
    void deepMergeContainer_bothEmpty() {
        var result = FeatureStoreUtil.deepMerge(
            FeatureStoreResponseContainer.empty(),
            FeatureStoreResponseContainer.empty()
        );
        assertThat(result.featureStoreResponses()).isEmpty();
    }

    @Test
    void deepMergeContainer_firstEmpty() {
        var response = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );
        var second = new FeatureStoreResponseContainer(ImmutableMap.of("view1_v1", response));

        var result = FeatureStoreUtil.deepMerge(FeatureStoreResponseContainer.empty(), second);

        assertThat(result.featureStoreResponses()).hasSize(1);
        assertThat(result.featureStoreResponses().get("view1_v1")).isNotSameAs(response);
        assertThat(result.featureStoreResponses().get("view1_v1").getAllEntities()).isEqualTo(response.getAllEntities());
    }

    @Test
    void deepMergeContainer_secondEmpty() {
        var response = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );
        var first = new FeatureStoreResponseContainer(ImmutableMap.of("view1_v1", response));

        var result = FeatureStoreUtil.deepMerge(first, FeatureStoreResponseContainer.empty());

        assertThat(result.featureStoreResponses()).hasSize(1);
        assertThat(result.featureStoreResponses().get("view1_v1")).isNotSameAs(response);
        assertThat(result.featureStoreResponses().get("view1_v1").getAllEntities()).isEqualTo(response.getAllEntities());
    }

    @Test
    void deepMergeContainer_firstHasViewSecondDoesNot() {
        var response = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );
        var first = new FeatureStoreResponseContainer(ImmutableMap.of("view1_v1", response));
        var second = new FeatureStoreResponseContainer(ImmutableMap.of("view2_v1", response));

        var result = FeatureStoreUtil.deepMerge(first, second);

        assertThat(result.featureStoreResponses()).hasSize(2);
        assertThat(result.featureStoreResponses().get("view1_v1")).isNotSameAs(response);
        assertThat(result.featureStoreResponses().get("view1_v1").getAllEntities()).isEqualTo(response.getAllEntities());
    }

    @Test
    void deepMergeContainer_secondHasViewFirstDoesNot() {
        var response = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );
        var first = new FeatureStoreResponseContainer(ImmutableMap.of("view1_v1", response));
        var second = new FeatureStoreResponseContainer(ImmutableMap.of("view2_v1", response));

        var result = FeatureStoreUtil.deepMerge(first, second);

        assertThat(result.featureStoreResponses()).hasSize(2);
        assertThat(result.featureStoreResponses().get("view2_v1")).isNotSameAs(response);
        assertThat(result.featureStoreResponses().get("view2_v1").getAllEntities()).isEqualTo(response.getAllEntities());
    }

    @Test
    void deepMergeContainer_invalidArguments() {
        var container = FeatureStoreResponseContainer.empty();
        assertThatThrownBy(() -> FeatureStoreUtil.deepMerge(null, container))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("first container cannot be null");
        assertThatThrownBy(() -> FeatureStoreUtil.deepMerge(container, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("second container cannot be null");
    }

    // ==================== deepMerge() Response tests ====================

    @Test
    void deepMergeResponse_bothSuccess() {
        Map<String, Object> entityId = Map.of("id", "1");
        var first = SimpleFeatureStoreResponse.success(
            Map.of(entityId, Map.of("feature1", "value1"))
        );
        var second = SimpleFeatureStoreResponse.success(
            Map.of(entityId, Map.of("feature2", "value2"))
        );

        var result = FeatureStoreUtil.deepMerge(first, second);

        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getRequestFailure()).isEmpty();
        var mergedEntity = result.getEntity(entityId);
        assertThat(mergedEntity).containsEntry("feature1", "value1");
        assertThat(mergedEntity).containsEntry("feature2", "value2");
    }

    @Test
    void deepMergeResponse_successWithMultipleEntities() {
        Map<String, Object> entity1 = Map.of("id", "1");
        Map<String, Object> entity2 = Map.of("id", "2");
        var first = SimpleFeatureStoreResponse.success(
            Map.of(entity1, Map.of("feature1", "value1"))
        );
        var second = SimpleFeatureStoreResponse.success(
            Map.of(entity2, Map.of("feature2", "value2"))
        );

        var result = FeatureStoreUtil.deepMerge(first, second);

        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getAllEntities()).hasSize(2);
        assertThat(result.getEntity(entity1)).containsEntry("feature1", "value1");
        assertThat(result.getEntity(entity2)).containsEntry("feature2", "value2");
    }

    @Test
    void deepMergeResponse_successWithOverlappingEntities() {
        Map<String, Object> entityId = Map.of("id", "1");
        var first = SimpleFeatureStoreResponse.success(
            Map.of(entityId, Map.of("feature1", "value1", "feature2", "value2"))
        );
        var second = SimpleFeatureStoreResponse.success(
            Map.of(entityId, Map.of("feature2", "new_value2", "feature3", "value3"))
        );

        var result = FeatureStoreUtil.deepMerge(first, second);

        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getAllEntities()).hasSize(1);
        var mergedEntity = result.getEntity(entityId);
        assertThat(mergedEntity).containsEntry("feature1", "value1");
        assertThat(mergedEntity).containsEntry("feature2", "new_value2");
        assertThat(mergedEntity).containsEntry("feature3", "value3");
    }

    @Test
    void deepMergeResponse_bothFailure() {
        var first = FailedRequestFeatureStoreResponse.withFailure("First failure");
        var second = FailedRequestFeatureStoreResponse.withFailure("Second failure");

        var result = FeatureStoreUtil.deepMerge(first, second);

        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getRequestFailure()).isPresent();
        assertThat(result.getRequestFailure().get()).contains("First failure");
        assertThat(result.getRequestFailure().get()).contains("Second failure");
        assertThat(result.getAllEntities()).isEmpty();
    }

    @Test
    void deepMergeResponse_failureAndSuccess() {
        Map<String, Object> entityId = Map.of("id", "1");
        var failure = FailedRequestFeatureStoreResponse.withFailure("Failure message");
        var success = SimpleFeatureStoreResponse.success(
            Map.of(entityId, Map.of("feature1", "value1"))
        );

        var result = FeatureStoreUtil.deepMerge(failure, success);

        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getRequestFailure()).isPresent();
        assertThat(result.getRequestFailure().get()).isEqualTo("Failure message");
        assertThat(result.getAllEntities()).isNotEmpty();
        assertThat(result.getEntity(entityId)).containsEntry("feature1", "value1");
    }

    @Test
    void deepMergeResponse_partialFailureAndSuccess() {
        Map<String, Object> entityId1 = Map.of("id", "1");
        Map<String, Object> entityId2 = Map.of("id", "2");
        var partial = SimpleFeatureStoreResponse.partial(
            Map.of(entityId1, Map.of("feature1", "value1")),
            "Partial failure"
        );
        var success = SimpleFeatureStoreResponse.success(
            Map.of(entityId2, Map.of("feature2", "value2"))
        );

        var result = FeatureStoreUtil.deepMerge(partial, success);

        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getRequestFailure()).isPresent();
        assertThat(result.getRequestFailure().get()).isEqualTo("Partial failure");
        assertThat(result.getAllEntities()).hasSize(2);
        assertThat(result.getEntity(entityId1)).containsEntry("feature1", "value1");
        assertThat(result.getEntity(entityId2)).containsEntry("feature2", "value2");
    }

    @Test
    void deepMergeResponse_invalidArguments() {
        var response = SimpleFeatureStoreResponse.success(Map.of());
        assertThatThrownBy(() -> FeatureStoreUtil.deepMerge(null, response))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("first response cannot be null");
        assertThatThrownBy(() -> FeatureStoreUtil.deepMerge(response, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("second response cannot be null");
    }

    // ==================== deepCopy() Container tests ====================

    @Test
    void deepCopyContainer_withResponses() {
        var response1 = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );
        var response2 = SimpleFeatureStoreResponse.failure("Error");
        var container = new FeatureStoreResponseContainer(ImmutableMap.of(
            "view1_v1", response1,
            "view2_v1", response2
        ));

        var result = FeatureStoreUtil.deepCopy(container);

        assertThat(result.featureStoreResponses()).hasSize(2);
        assertThat(result.featureStoreResponses().get("view1_v1")).isNotSameAs(response1);
        assertThat(result.featureStoreResponses().get("view2_v1")).isNotSameAs(response2);
        assertThat(result.featureStoreResponses().get("view1_v1").getAllEntities()).isEqualTo(response1.getAllEntities());
        assertThat(result.featureStoreResponses().get("view2_v1").getRequestFailure()).isEqualTo(response2.getRequestFailure());
    }

    @Test
    void deepCopyContainer_empty() {
        var result = FeatureStoreUtil.deepCopy(FeatureStoreResponseContainer.empty());
        assertThat(result.featureStoreResponses()).isEmpty();
    }

    @Test
    void deepCopyContainer_createsNewInstance() {
        var response = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );
        var container = new FeatureStoreResponseContainer(ImmutableMap.of("view1_v1", response));

        var result = FeatureStoreUtil.deepCopy(container);

        assertThat(result).isNotSameAs(container);
        assertThat(result.featureStoreResponses()).isNotSameAs(container.featureStoreResponses());
        assertThat(result.featureStoreResponses().get("view1_v1")).isNotSameAs(response);
    }

    @Test
    void deepCopyContainer_invalidArguments() {
        assertThatThrownBy(() -> FeatureStoreUtil.deepCopy((FeatureStoreResponseContainer) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("container cannot be null");
    }

    // ==================== deepCopy() Response tests ====================

    @Test
    void deepCopyResponse_success() {
        var response = SimpleFeatureStoreResponse.success(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1"))
        );

        var result = FeatureStoreUtil.deepCopy(response);

        assertThat(result).isNotSameAs(response);
        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getAllEntities()).isEqualTo(response.getAllEntities());
        assertThat(result.getRequestFailure()).isEmpty();
    }

    @Test
    void deepCopyResponse_failure() {
        var response = FailedRequestFeatureStoreResponse.withFailure("Error message");

        var result = FeatureStoreUtil.deepCopy(response);

        assertThat(result).isNotSameAs(response);
        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getRequestFailure()).isEqualTo(response.getRequestFailure());
        assertThat(result.getAllEntities()).isEmpty();
    }

    @Test
    void deepCopyResponse_partialFailure() {
        var response = SimpleFeatureStoreResponse.partial(
            Map.of(Map.of("id", "1"), Map.of("feature1", "value1")),
            "Some failure"
        );

        var result = FeatureStoreUtil.deepCopy(response);

        assertThat(result).isNotSameAs(response);
        assertThat(result).isInstanceOf(SimpleFeatureStoreResponse.class);
        assertThat(result.getAllEntities()).isEqualTo(response.getAllEntities());
        assertThat(result.getRequestFailure()).isEqualTo(response.getRequestFailure());
    }

    @Test
    void deepCopyResponse_invalidArguments() {
        assertThatThrownBy(() -> FeatureStoreUtil.deepCopy((FeatureStoreResponse) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("response cannot be null");
    }

}
