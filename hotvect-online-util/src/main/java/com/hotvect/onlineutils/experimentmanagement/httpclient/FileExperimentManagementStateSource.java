package com.hotvect.onlineutils.experimentmanagement.httpclient;

import com.hotvect.onlineutils.experimentmanagement.ExperimentManagementStateSource;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Immutable active EMS state read from a local JSON document. */
public final class FileExperimentManagementStateSource implements ExperimentManagementStateSource {
    private final Map<String, Slot> slots;
    private final EmsStateSnapshotDocument.CaptureProvenance provenance;

    public FileExperimentManagementStateSource(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        EmsStateSnapshotDocument document = EmsStateSnapshotDocument.read(path);
        TreeMap<String, Slot> resolved = new TreeMap<>();
        document.slots().forEach((slotName, state) -> {
            if (slotName == null || slotName.isBlank()) {
                throw new IllegalArgumentException("EMS state slot names must not be blank");
            }
            resolved.put(slotName, EmsSlotStateMapper.toSlot(state));
        });
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("EMS state must contain at least one slot");
        }
        this.slots = Collections.unmodifiableMap(resolved);
        this.provenance = document.provenance();
    }

    /** Returns every slot captured in this immutable state document. */
    public Set<String> slotNames() {
        return slots.keySet();
    }

    /** Returns capture provenance when the state document was exported from live EMS. */
    public Optional<EmsStateSnapshotDocument.CaptureProvenance> provenance() {
        return Optional.ofNullable(provenance);
    }

    @Override
    public Slot getDefaultVariantAndActiveExperiments(String slotName) {
        Slot slot = slots.get(slotName);
        if (slot == null) {
            throw new IllegalArgumentException("EMS state does not contain slot " + slotName);
        }
        return slot;
    }

    @Override
    public void close() {
    }
}
