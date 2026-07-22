package com.hotvect.onlineutils.experimentmanagement.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ExperimentManagementPayloadDeserializationTest {

    @Test
    void deserializesSlotPayloadIntoRecordBasedDtos() throws IOException {
        String payload = """
                {
                  "slot_salt": "salt-1",
                  "total_number_of_shards": 100,
                  "default_variant": {
                    "variant_id": 1,
                    "algorithm": {
                      "algorithm_name": "algo-a",
                      "algorithm_version": "1.0.0",
                      "latest_algorithm_parameter": "param-1",
                      "absolute_s3_algorithm_jar_path": "s3://bucket/algo-a.jar",
                      "absolute_s3_algorithm_parameter_path": "s3://bucket/param-1.zip"
                    },
                    "created_at": "2026-04-11T10:15:30Z",
                    "is_control": true,
                    "is_default": true,
                    "shard_allocation_ratio": 100
                  },
                  "experiments": [
                    {
                      "experiment_id": 42,
                      "experiment_name": "test-exp",
                      "variants": [
                        {
                          "variant_id": 7,
                          "algorithm": {
                            "algorithm_name": "algo-b",
                            "algorithm_version": "2.0.0",
                            "latest_algorithm_parameter": "param-7",
                            "absolute_s3_algorithm_jar_path": "s3://bucket/algo-b.jar",
                            "absolute_s3_algorithm_parameter_path": "s3://bucket/param-7.zip"
                          },
                          "created_at": "2026-04-11T10:15:30Z",
                          "is_control": false,
                          "is_default": false,
                          "shard_allocation_ratio": 100
                        }
                      ],
                      "ramp_up_percentage": 50,
                      "shards": [
                        {
                          "shard_id": 1,
                          "created_at": "2026-04-11T10:15:30Z"
                        },
                        {
                          "shard_id": 2,
                          "created_at": "2026-04-11T10:15:30Z"
                        },
                        {
                          "shard_id": 3,
                          "created_at": "2026-04-11T10:15:30Z"
                        }
                      ]
                    }
                  ],
                  "user_forced_assignments": [
                    {
                      "user_id": "user-1",
                      "variant_id": 7
                    }
                  ]
                }
                """;

        Slot slot = ExperimentManagementServiceClient.objectMapper().readValue(payload, Slot.class);

        assertEquals("salt-1", slot.slotSalt());
        assertEquals(100, slot.totalNumberOfShards());
        assertEquals(1, slot.defaultVariant().variantId());
        assertEquals("algo-a", slot.defaultVariant().algorithm().algorithmName());
        assertEquals(Boolean.TRUE, slot.defaultVariant().isControl());
        assertEquals(Boolean.TRUE, slot.defaultVariant().isDefault());
        assertEquals(100, slot.defaultVariant().shardAllocationRatio());
        assertEquals(1, slot.experiments().size());
        assertEquals(42, slot.experiments().getFirst().experimentId());
        assertEquals("test-exp", slot.experiments().getFirst().experimentName());
        assertEquals(50, slot.experiments().getFirst().rampUpPercentage());
        assertEquals(3, slot.experiments().getFirst().shards().size());
        assertEquals(1, slot.experiments().getFirst().shards().getFirst().shardId());
        assertEquals(7, slot.experiments().getFirst().variants().getFirst().variantId());
        assertEquals("user-1", slot.userForcedAssignments().getFirst().userId());
        assertEquals(7, slot.userForcedAssignments().getFirst().variantId());
    }

    @Test
    void deserializesMissingListsAsEmptyLists() throws IOException {
        String payload = """
                {
                  "slot_salt": "salt-1",
                  "total_number_of_shards": 100,
                  "default_variant": {
                    "variant_id": 1,
                    "algorithm": {
                      "algorithm_name": "algo-a",
                      "algorithm_version": "1.0.0",
                      "latest_algorithm_parameter": "param-1",
                      "absolute_s3_algorithm_jar_path": "s3://bucket/algo-a.jar",
                      "absolute_s3_algorithm_parameter_path": "s3://bucket/param-1.zip"
                    },
                    "created_at": "2026-04-11T10:15:30Z",
                    "is_control": true,
                    "is_default": true,
                    "shard_allocation_ratio": 100
                  }
                }
                """;

        Slot slot = ExperimentManagementServiceClient.objectMapper().readValue(payload, Slot.class);

        assertEquals(0, slot.experiments().size());
        assertEquals(0, slot.userForcedAssignments().size());
    }

    @Test
    void failsFastWhenDefaultVariantIsMissing() {
        String payload = """
                {
                  "slot_salt": "salt-1",
                  "total_number_of_shards": 100,
                  "experiments": [],
                  "user_forced_assignments": []
                }
                """;

        ValueInstantiationException exception = assertThrows(
                ValueInstantiationException.class,
                () -> ExperimentManagementServiceClient.objectMapper().readValue(payload, Slot.class));

        assertEquals("defaultVariant must not be null", exception.getCause().getMessage());
    }
}
