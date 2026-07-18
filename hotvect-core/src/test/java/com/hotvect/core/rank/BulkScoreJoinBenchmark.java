package com.hotvect.core.rank;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
public class BulkScoreJoinBenchmark {
    // HashMap and explicit validation variants are comparison baselines. Production uses assertion-guarded
    // scorer order validation, then joins by index.
    @Param({"10", "100", "1000", "5000"})
    int actionCount;

    List<AvailableAction<Integer>> actions;
    List<ScoringDecision<Integer>> scoresWithIdsInOrder;
    List<ScoringDecision<Integer>> scoresWithIdsReversed;
    List<ScoringDecision<Integer>> scoresWithoutIds;

    @Setup
    public void setUp() {
        actions = new ArrayList<>(actionCount);
        scoresWithIdsInOrder = new ArrayList<>(actionCount);
        scoresWithIdsReversed = new ArrayList<>(actionCount);
        scoresWithoutIds = new ArrayList<>(actionCount);

        for (int i = 0; i < actionCount; i++) {
            String actionId = "sku-" + i;
            actions.add(AvailableAction.of(actionId, i));
            scoresWithIdsInOrder.add(ScoringDecision.of(actionId, i, score(i)));
            scoresWithoutIds.add(ScoringDecision.of(i, score(i)));
        }
        for (int i = actionCount - 1; i >= 0; i--) {
            String actionId = "sku-" + i;
            scoresWithIdsReversed.add(ScoringDecision.of(actionId, i, score(i)));
        }
    }

    @Benchmark
    public double indexJoin() {
        return indexJoin(actions, scoresWithIdsInOrder);
    }

    @Benchmark
    public double alternativeHashMapJoinInOrder() {
        return alternativeHashMapJoin(actions, scoresWithIdsInOrder);
    }

    @Benchmark
    public double alternativeHashMapJoinReversed() {
        return alternativeHashMapJoin(actions, scoresWithIdsReversed);
    }

    @Benchmark
    public double legacyNullIdDetectionThenIndexJoin() {
        return legacyNullIdDetectionThenIndexJoin(actions, scoresWithoutIds);
    }

    @Benchmark
    public double validateIdsThenIndexJoin() {
        return validateIdsThenIndexJoin(actions, scoresWithIdsInOrder);
    }

    @Benchmark
    public double assertionGuardedValidationThenIndexJoin() {
        assert validateIds(actions, scoresWithIdsInOrder);
        return indexJoin(actions, scoresWithIdsInOrder);
    }

    private static double indexJoin(
            List<AvailableAction<Integer>> actions,
            List<ScoringDecision<Integer>> scores
    ) {
        double ret = 0.0;
        for (int i = 0; i < actions.size(); i++) {
            ret += scores.get(i).score();
        }
        return ret;
    }

    private static double validateIdsThenIndexJoin(
            List<AvailableAction<Integer>> actions,
            List<ScoringDecision<Integer>> scores
    ) {
        validateIds(actions, scores);
        return indexJoin(actions, scores);
    }

    private static boolean validateIds(
            List<AvailableAction<Integer>> actions,
            List<ScoringDecision<Integer>> scores
    ) {
        for (int i = 0; i < actions.size(); i++) {
            ScoringDecision<Integer> score = scores.get(i);
            String scoreActionId = score.actionId();
            if (!actions.get(i).actionId().equals(scoreActionId)) {
                throw new IllegalArgumentException("score action id does not match request position");
            }
        }
        return true;
    }

    private static double legacyNullIdDetectionThenIndexJoin(
            List<AvailableAction<Integer>> actions,
            List<ScoringDecision<Integer>> scores
    ) {
        for (ScoringDecision<Integer> score : scores) {
            if (score.actionId() != null) {
                throw new IllegalArgumentException("score action id was expected to be null");
            }
        }
        return indexJoin(actions, scores);
    }

    private static double alternativeHashMapJoin(
            List<AvailableAction<Integer>> actions,
            List<ScoringDecision<Integer>> scores
    ) {
        Map<String, ScoringDecision<Integer>> scoresByActionId = scoresByActionId(scores);
        double ret = 0.0;
        for (int i = 0; i < actions.size(); i++) {
            ScoringDecision<Integer> score = scoresByActionId.get(actions.get(i).actionId());
            ret += score.score();
        }
        return ret;
    }

    private static Map<String, ScoringDecision<Integer>> scoresByActionId(List<ScoringDecision<Integer>> scores) {
        Map<String, ScoringDecision<Integer>> ret = new HashMap<>(scores.size());
        for (ScoringDecision<Integer> score : scores) {
            ret.put(score.actionId(), score);
        }
        return ret;
    }

    private static double score(int i) {
        return i * 0.0001;
    }
}
