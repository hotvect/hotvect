package com.hotvect.onlineutils.experimentmanagement.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileExperimentManagementStateSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void readsExactActiveSlotPayloadsFromOneImmutableDocument() throws Exception {
        Path state = tempDir.resolve("ems-state.json");
        Files.writeString(state, """
                {
                  "slots": {
                    "product-ranking": {
                      "slot_salt": "ranking-salt",
                      "total_number_of_shards": 100,
                      "default_variant": {
                        "variant_id": 1,
                        "algorithm": {
                          "algorithm_name": "default-ranker",
                          "algorithm_version": "1.0.0",
                          "absolute_s3_algorithm_jar_path": "file:///tmp/default-ranker.jar"
                        },
                        "created_at": "2026-04-11T10:15:30Z",
                        "is_default": true
                      },
                      "experiments": [{
                        "experiment_id": 42,
                        "experiment_name": "ranking-test",
                        "variants": [{
                          "variant_id": 2,
                          "algorithm": {
                            "algorithm_name": "treatment-ranker",
                            "algorithm_version": "2.0.0",
                            "latest_algorithm_parameter": "parameter-2",
                            "absolute_s3_algorithm_jar_path": "file:///tmp/treatment-ranker.jar",
                            "absolute_s3_algorithm_parameter_path": "file:///tmp/parameter-2.zip"
                          },
                          "created_at": "2026-04-11T10:15:30Z",
                          "shard_allocation_ratio": 100
                        }],
                        "ramp_up_percentage": 50,
                        "shards": [{"shard_id": 1, "created_at": "2026-04-11T10:15:30Z"}],
                        "created_at": "2026-04-11T10:15:30Z"
                      }],
                      "user_forced_assignments": [{"user_id": "forced-user", "variant_id": 2}]
                    }
                  },
                  "provenance": {
                    "schema_version": 1,
                    "source_uri": "https://ems.example",
                    "requested_root_slot": "product-ranking",
                    "capture_started_at": "2026-04-11T10:15:00Z",
                    "capture_completed_at": "2026-04-11T10:16:00Z",
                    "slot_captured_at": {"product-ranking": "2026-04-11T10:15:30Z"},
                    "read_consistency": "independently_fetched_per_slot_non_transactional"
                  }
                }
                """);

        try (FileExperimentManagementStateSource source = new FileExperimentManagementStateSource(state)) {
            var slot = source.getDefaultVariantAndActiveExperiments("product-ranking");

            assertEquals(1, slot.defaultVariant().variantId());
            assertEquals(1, slot.experiments().size());
            assertEquals(1, slot.userForcedAssignments().size());
            assertEquals(java.util.Set.of("product-ranking"), source.slotNames());
            assertEquals("product-ranking", source.provenance().orElseThrow().requestedRootSlot());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> source.getDefaultVariantAndActiveExperiments("missing-slot"));
        }
    }

    @Test
    void rejectsUnknownDocumentFields() throws Exception {
        Path state = tempDir.resolve("ems-state.json");
        Files.writeString(state, "{\"slots\":{},\"fallback\":true}");

        UnrecognizedPropertyException failure = assertThrows(
                UnrecognizedPropertyException.class,
                () -> new FileExperimentManagementStateSource(state));

        assertEquals("fallback", failure.getPropertyName());
    }

    @Test
    void rejectsProvenanceThatDoesNotDescribeEveryCapturedSlot() throws Exception {
        Path state = tempDir.resolve("ems-state.json");
        Files.writeString(
                state,
                """
                {
                  "slots": {},
                  "provenance": {
                    "schema_version": 1,
                    "source_uri": "https://ems.example",
                    "requested_root_slot": "product-ranking",
                    "capture_started_at": "2026-04-11T10:15:00Z",
                    "capture_completed_at": "2026-04-11T10:16:00Z",
                    "slot_captured_at": {"product-ranking": "2026-04-11T10:15:30Z"},
                    "read_consistency": "independently_fetched_per_slot_non_transactional"
                  }
                }
                """);

        IOException failure = assertThrows(
                IOException.class,
                () -> new FileExperimentManagementStateSource(state));

        assertTrue(failure.getMessage().contains("timestamps"));
    }

    @Test
    void rejectsDuplicateAndTrailingStateContent() throws Exception {
        Path duplicateSlots = tempDir.resolve("duplicate-slots.json");
        Files.writeString(duplicateSlots, "{\"slots\":{},\"slots\":{}}");

        assertThrows(IOException.class, () -> new FileExperimentManagementStateSource(duplicateSlots));

        Path trailingDocument = tempDir.resolve("trailing-document.json");
        Files.writeString(
                trailingDocument,
                """
                {
                  "slots": {
                    "product-ranking": {
                      "slot_salt": "ranking-salt",
                      "total_number_of_shards": 1,
                      "default_variant": {
                        "variant_id": 1,
                        "algorithm": {
                          "algorithm_name": "default-ranker",
                          "algorithm_version": "1.0.0",
                          "absolute_s3_algorithm_jar_path": "file:///tmp/default-ranker.jar"
                        },
                        "created_at": "2026-04-11T10:15:30Z",
                        "is_default": true
                      },
                      "experiments": [],
                      "user_forced_assignments": []
                    }
                  }
                }
                {"not":"part of the state document"}
                """);

        assertThrows(IOException.class, () -> new FileExperimentManagementStateSource(trailingDocument));
    }

    @Test
    void rejectsEmptyState() throws Exception {
        Path state = tempDir.resolve("ems-state.json");
        Files.writeString(state, "{\"slots\":{}}");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new FileExperimentManagementStateSource(state));

        assertTrue(failure.getMessage().contains("at least one slot"));
    }
}
