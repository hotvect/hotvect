package com.hotvect.core.rank;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
public class RankingRequestConstructionBenchmark {
    @Param({"10", "100", "1000", "5000"})
    int actionCount;

    List<AvailableAction<Integer>> actions;

    @Setup
    public void setUp() {
        actions = new ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) {
            actions.add(AvailableAction.of("sku-" + i, i));
        }
    }

    @Benchmark
    public int rankingRequestConstruction() {
        return RankingRequest.ofAvailableActions("example", null, actions).actions().size();
    }
}
