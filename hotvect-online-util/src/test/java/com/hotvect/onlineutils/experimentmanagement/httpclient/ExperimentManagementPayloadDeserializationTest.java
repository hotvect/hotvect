package com.hotvect.onlineutils.experimentmanagement.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ExperimentManagementPayloadDeserializationTest {

    @Test
    void mapsSingletonDefaultAndExperimentVariants() throws IOException {
        Slot slot = ExperimentManagementServiceClient.decodeSlotState(slotActiveInfoPayload());

        var defaultAlgorithm = slot.defaultVariant().algorithm();
        assertEquals("singleton-default", defaultAlgorithm.algorithmName());
        assertEquals("singleton-default-param", defaultAlgorithm.latestAlgorithmParameter());
        assertEquals("s3://bucket/singleton-default.zip", defaultAlgorithm.absoluteS3AlgorithmParameterPath());

        var experimentAlgorithm = slot.experiments()
                .getFirst()
                .variants()
                .getFirst()
                .algorithm();
        assertEquals("singleton-experiment", experimentAlgorithm.algorithmName());
        assertEquals("singleton-experiment-param", experimentAlgorithm.latestAlgorithmParameter());
        assertEquals("s3://bucket/singleton-experiment.zip", experimentAlgorithm.absoluteS3AlgorithmParameterPath());
    }

    @Test
    void mapsParameterlessAlgorithmSelection() throws IOException {
        String payload = """
                {
                  "slot_salt": "salt-1",
                  "total_number_of_shards": 100,
                  "default_variant": {
                    "variant_id": 1,
                    "algorithm": {
                      "algorithm_name": "parameterless",
                      "algorithm_version": "1.0.0",
                      "absolute_s3_algorithm_jar_path": "s3://bucket/parameterless.jar"
                    },
                    "created_at": "2026-04-11T10:15:30Z"
                  },
                  "experiments": [],
                  "user_forced_assignments": []
                }
                """;

        var algorithm = ExperimentManagementServiceClient.decodeSlotState(payload)
                .defaultVariant()
                .algorithm();

        assertEquals("parameterless", algorithm.algorithmName());
        assertNull(algorithm.latestAlgorithmParameter());
        assertNull(algorithm.absoluteS3AlgorithmParameterPath());
    }

    @Test
    void rejectsMissingRequiredAlgorithm() {
        String payload = slotActiveInfoPayload().replace(
                """
                    "algorithm": {
                      "algorithm_name": "singleton-default",
                      "algorithm_version": "1.0.0",
                      "latest_algorithm_parameter": "singleton-default-param",
                      "absolute_s3_algorithm_jar_path": "s3://bucket/singleton-default.jar",
                      "absolute_s3_algorithm_parameter_path": "s3://bucket/singleton-default.zip"
                    },
                """,
                "");

        MismatchedInputException exception = assertThrows(
                MismatchedInputException.class,
                () -> ExperimentManagementServiceClient.decodeSlotState(payload));

        assertEquals("algorithm", exception.getPath().getLast().getFieldName());
    }

    @Test
    void rejectsPluralAlgorithmsWireProperty() {
        String payload = slotActiveInfoPayload().replace(
                """
                    "created_at": "2026-04-11T10:15:30Z",
                """,
                """
                    "algorithms": [],
                    "created_at": "2026-04-11T10:15:30Z",
                """);

        assertThrows(
                UnrecognizedPropertyException.class,
                () -> ExperimentManagementServiceClient.decodeSlotState(payload));
    }

    @Test
    void rejectsUnexpectedWireProperties() {
        String payload = slotActiveInfoPayload().replace(
                "\"slot_salt\": \"salt-1\"",
                "\"slot_salt\": \"salt-1\", \"unexpected\": true");

        assertThrows(
                UnrecognizedPropertyException.class,
                () -> ExperimentManagementServiceClient.decodeSlotState(payload));
    }

    @Test
    void rejectsEmsParameterPathWithoutParameterIdentity() {
        String payload = slotActiveInfoPayload().replace(
                "\"latest_algorithm_parameter\": \"singleton-default-param\",",
                "");

        assertThrows(
                IllegalArgumentException.class,
                () -> ExperimentManagementServiceClient.decodeSlotState(payload));
    }

    private static String slotActiveInfoPayload() {
        return """
                {
                  "slot_salt": "salt-1",
                  "total_number_of_shards": 100,
                  "default_variant": {
                    "variant_id": 1,
                    "algorithm": {
                      "algorithm_name": "singleton-default",
                      "algorithm_version": "1.0.0",
                      "latest_algorithm_parameter": "singleton-default-param",
                      "absolute_s3_algorithm_jar_path": "s3://bucket/singleton-default.jar",
                      "absolute_s3_algorithm_parameter_path": "s3://bucket/singleton-default.zip"
                    },
                    "created_at": "2026-04-11T10:15:30Z",
                    "is_control": true,
                    "is_default": true,
                    "shard_allocation_ratio": 100
                  },
                  "experiments": [
                    {
                      "experiment_id": 42,
                      "experiment_name": "singleton-exp",
                      "variants": [
                        {
                          "variant_id": 7,
                          "algorithm": {
                            "algorithm_name": "singleton-experiment",
                            "algorithm_version": "2.0.0",
                            "latest_algorithm_parameter": "singleton-experiment-param",
                            "absolute_s3_algorithm_jar_path": "s3://bucket/singleton-experiment.jar",
                            "absolute_s3_algorithm_parameter_path": "s3://bucket/singleton-experiment.zip"
                          },
                          "created_at": "2026-04-11T10:15:30Z",
                          "is_control": false,
                          "is_default": false,
                          "shard_allocation_ratio": 100
                        }
                      ],
                      "ramp_up_percentage": 50,
                      "shards": [],
                      "created_at": "2026-04-11T10:15:30Z"
                    }
                  ],
                  "user_forced_assignments": []
                }
                """;
    }
}
