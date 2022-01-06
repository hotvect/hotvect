package com.eshioji.hotvect.api.data.raw.ccb;

import com.google.common.math.DoubleMath;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.math.DoubleMath.fuzzyEquals;

public class Decision {
    private final int slotIndex;
    private final int actionIndex;
    private final Double probability;
    private final Int2DoubleMap otherActions2Probabilities;

    public Decision(int slotIndex, int actionIndex) {
        this.slotIndex = slotIndex;
        this.actionIndex = actionIndex;
        this.probability = null;
        this.otherActions2Probabilities = null;
    }

    public Decision(int slotIndex, int actionIndex,@Nonnull Double probability) {
        this.slotIndex = slotIndex;
        this.actionIndex = actionIndex;
        this.probability = probability;
        this.otherActions2Probabilities = null;
    }

    public Decision(int slotIndex, int actionIndex, @Nonnull Double probability, @Nonnull Int2DoubleMap otherActions2Probabilities) {
        this.slotIndex = slotIndex;
        this.actionIndex = actionIndex;
        this.probability = probability;
        this.otherActions2Probabilities = otherActions2Probabilities;
        var sumP = probability + Arrays.stream(otherActions2Probabilities.values().toDoubleArray()).sum();
        checkArgument(fuzzyEquals(sumP, 1.0, 1E-7),
                "Probabilities are provided, but they do not sum up to 1.0. Instead:%s", sumP);
    }


    public int getActionIndex() {
        return actionIndex;
    }

    @Nullable
    public Double getProbability() {
        return probability;
    }

    @Nullable
    public Int2DoubleMap getOtherActions2Probabilities() {
        return otherActions2Probabilities;
    }

    public int getSlotIndex() {
        return slotIndex;
    }
}
