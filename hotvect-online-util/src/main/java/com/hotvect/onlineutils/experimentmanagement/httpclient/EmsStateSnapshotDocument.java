package com.hotvect.onlineutils.experimentmanagement.httpclient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Strict, immutable active-slot state document consumed by offline EMS prediction. */
public record EmsStateSnapshotDocument(
        @JsonProperty(value = "slots", required = true) Map<String, SlotActiveInfo> slots,
        @JsonProperty("provenance") CaptureProvenance provenance) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    public EmsStateSnapshotDocument {
        slots = immutableSlots(slots);
        if (provenance != null && !provenance.slotCapturedAt().keySet().equals(slots.keySet())) {
            throw new IllegalArgumentException(
                    "EMS snapshot provenance timestamps must identify exactly the captured slots");
        }
    }

    public static EmsStateSnapshotDocument read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        try (var input = Files.newInputStream(path)) {
            return OBJECT_MAPPER.readValue(input, EmsStateSnapshotDocument.class);
        }
    }

    /** Creates the output directory and atomically writes a completed document without replacing an existing file. */
    public static void writeAtomically(Path output, EmsStateSnapshotDocument document) throws IOException {
        Path target = Objects.requireNonNull(output, "output must not be null").toAbsolutePath();
        Objects.requireNonNull(document, "document must not be null");
        if (Files.exists(target)) {
            throw new IllegalArgumentException("EMS snapshot output already exists: " + target);
        }
        Path parent = Objects.requireNonNull(target.getParent(), "output must have a parent directory");
        Files.createDirectories(parent);
        String fileName = Objects.requireNonNull(target.getFileName(), "output must name a file").toString();
        Path temporary = Files.createTempFile(parent, "." + fileName + ".", ".tmp");
        boolean completed = false;
        try {
            try (var stream = Files.newOutputStream(temporary)) {
                OBJECT_MAPPER.writeValue(stream, document);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Map<String, SlotActiveInfo> immutableSlots(Map<String, SlotActiveInfo> slots) {
        TreeMap<String, SlotActiveInfo> copied = new TreeMap<>();
        Objects.requireNonNull(slots, "slots must not be null").forEach((name, state) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("EMS state slot names must not be blank");
            }
            copied.put(name, Objects.requireNonNull(state, "EMS state slot must not be null"));
        });
        return Collections.unmodifiableMap(copied);
    }

    /** Provenance recorded only for snapshots captured from live EMS. */
    public record CaptureProvenance(
            @JsonProperty(value = "schema_version", required = true) int schemaVersion,
            @JsonProperty(value = "source_uri", required = true) String sourceUri,
            @JsonProperty(value = "requested_root_slot", required = true) String requestedRootSlot,
            @JsonProperty(value = "capture_started_at", required = true) Instant captureStartedAt,
            @JsonProperty(value = "capture_completed_at", required = true) Instant captureCompletedAt,
            @JsonProperty(value = "slot_captured_at", required = true) Map<String, Instant> slotCapturedAt,
            @JsonProperty(value = "read_consistency", required = true) String readConsistency) {
        public CaptureProvenance {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("Unsupported EMS snapshot provenance schema version: " + schemaVersion);
            }
            sourceUri = requireNonBlank(sourceUri, "sourceUri");
            requestedRootSlot = requireNonBlank(requestedRootSlot, "requestedRootSlot");
            captureStartedAt = Objects.requireNonNull(captureStartedAt, "captureStartedAt must not be null");
            captureCompletedAt = Objects.requireNonNull(captureCompletedAt, "captureCompletedAt must not be null");
            if (captureCompletedAt.isBefore(captureStartedAt)) {
                throw new IllegalArgumentException("captureCompletedAt must not precede captureStartedAt");
            }
            TreeMap<String, Instant> capturedSlots = new TreeMap<>();
            Objects.requireNonNull(slotCapturedAt, "slotCapturedAt must not be null").forEach((slot, capturedAt) ->
                    capturedSlots.put(requireNonBlank(slot, "slotCapturedAt slot"), Objects.requireNonNull(
                            capturedAt, "slotCapturedAt values must not be null")));
            if (capturedSlots.isEmpty()) {
                throw new IllegalArgumentException("slotCapturedAt must not be empty");
            }
            slotCapturedAt = Collections.unmodifiableMap(capturedSlots);
            readConsistency = requireNonBlank(readConsistency, "readConsistency");
        }

        private static String requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
