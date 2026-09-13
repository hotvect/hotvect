package com.hotvect.onlineutils.experimentmanagement;

import com.hotvect.onlineutils.experimentmanagement.models.Slot;

/** Supplies the active state used to assign one EMS slot. */
public interface ExperimentManagementStateSource extends AutoCloseable {
    Slot getDefaultVariantAndActiveExperiments(String slotName) throws Exception;

    @Override
    void close() throws Exception;
}
