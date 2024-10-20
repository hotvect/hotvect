package com.hotvect.vw.audit;

import com.hotvect.api.state.State;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public class VwAuditState implements State {
    private final Int2ObjectMap<VwAuditRecord> state;

    public VwAuditState(Int2DoubleMap parameters) {
        this.state = new Int2ObjectOpenHashMap<>(parameters.size());
        for (Int2DoubleMap.Entry entry : parameters.int2DoubleEntrySet()) {
            this.state.put(entry.getIntKey(), new VwAuditRecord(entry.getIntKey(), entry.getDoubleValue()));
        }
    }

    public Int2ObjectMap<VwAuditRecord> getState() {
        return state;
    }

    @Override
    public String getVersionId() {
        throw new AssertionError("not implemented");
    }
}
