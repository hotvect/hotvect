package com.eshioji.hotvect.api.data.ccb;

import com.eshioji.hotvect.api.data.ranking.RankingDecision;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.math.DoubleMath.fuzzyEquals;

public class CcbRankingDecision extends RankingDecision {
    private final Double probability;
    private final Int2DoubleMap otherActions2Probabilities;

    public CcbRankingDecision(int actionIndex) {
        super(actionIndex);
        this.probability = null;
        this.otherActions2Probabilities = null;
    }

    public CcbRankingDecision(int slotIndex, int actionIndex, @Nonnull Double probability) {
        super(actionIndex);
        this.probability = probability;
        this.otherActions2Probabilities = null;
    }

    public CcbRankingDecision(int slotIndex, int actionIndex, @Nonnull Double probability, @Nonnull Int2DoubleMap otherActions2Probabilities) {
        super(actionIndex);
        this.probability = probability;
        this.otherActions2Probabilities = otherActions2Probabilities;
        var sumP = probability + Arrays.stream(otherActions2Probabilities.values().toDoubleArray()).sum();
        checkArgument(fuzzyEquals(sumP, 1.0, 1E-7),
                "Probabilities are provided, but they do not sum up to 1.0. Instead:%s", sumP);
    }


    @Nullable
    public Double getProbability() {
        return probability;
    }

    @Nullable
    public Int2DoubleMap getOtherActions2Probabilities() {
        return otherActions2Probabilities;
    }
}
