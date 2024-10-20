package com.hotvect.vw;

import com.codahale.metrics.MetricRegistry;
import com.hotvect.utils.Pair;
import com.hotvect.util.CpuIntensiveAggregator;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;

public class VwModelImporter implements Function<BufferedReader, Int2DoubleMap> {
    private static final String NUMBER_PATTERN = "[-+]?[0-9]*\\.?[0-9]*([eE][-+]?[0-9]+)?";
    private static final Pattern WEIGHT_PATTERN = Pattern.compile("^(\\d+):(" + NUMBER_PATTERN + ")( "+NUMBER_PATTERN +" " + NUMBER_PATTERN + ")?$");

    private static class VwModelState {
        private final Int2DoubleOpenHashMap state = new Int2DoubleOpenHashMap(4096);
        private final ReentrantLock lock = new ReentrantLock();

        public double put(int key, double value) {
            lock.lock();
            try {
                return state.put(key, value);
            } finally {
                lock.unlock();
            }
        }

        public Int2DoubleOpenHashMap getState() {
            return this.state;
        }
    }

    @Override
    public Int2DoubleMap apply(BufferedReader parameter) {
        VwModelState state = new VwModelState();

        // First, skip the first non-weight bits
        try {
            for (String line = parameter.readLine(); line != null; line = parameter.readLine()) {
                if (tryExtractAndAppendWeight(line, state)) {
                    // We got to the weights, process the rest multi-threaded
                    break;
                } else {
                    // Have not reached the weights yet, keep going
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // The rest
        Stream<String> rest = parameter.lines();
        CpuIntensiveAggregator<VwModelState, String> aggregator = new CpuIntensiveAggregator<>(
                new MetricRegistry(),
                () -> state,
                (state1, line) -> {
                    checkState(tryExtractAndAppendWeight(line, state1),
                            "Weight section had started, but a line does not match the weight pattern. Offending line:" + line);
                    return state1;
                },
                3,
                3 * 4,
                1000
        );

        VwModelState aggregated = aggregator.aggregate(rest);
        checkState(aggregated == state);
        Int2DoubleOpenHashMap weights = aggregated.getState();
        checkState(weights.size() > 0, "Suspicious empty weights!");
        weights.trim();
        return weights;
    }

    private boolean tryExtractAndAppendWeight(String line, VwModelState acc) {
        Matcher m = WEIGHT_PATTERN.matcher(line);
        if (m.matches()) {
            int featureHash = Integer.parseInt(m.group(1));
            double weight = Double.parseDouble(m.group(2));
            acc.put(featureHash, weight);
            return true;
        } else {
            return false;
        }
    }

    private Pair<String, String> fastSplit(String s) {

        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') {
                count++;
            }
        }

        int a = -1;
        int b = 0;

        String first = null;
        String second;

        for (int i = 0; i < count; i++) {

            while (b < s.length() && s.charAt(b) != ':') {
                b++;
            }
            if (first == null){
                first = s.substring(a+1, b);
            } else {
                second = s.substring(a+1, b);
                return Pair.of(first, second);
            }
            a = b;
            b++;

        }
        throw new IllegalArgumentException("Cannot split:" + s);
    }
}
