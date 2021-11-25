package com.eshioji.hotvect.vw;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;

import java.util.List;
import java.util.Scanner;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkState;

public class VwModelImporter implements Function<Readable, Int2DoubleMap> {
    private static final Pattern WEIGHT_PATTERN = Pattern.compile("^(\\d+):[-+]?[0-9]*\\.?[0-9]*([eE][-+]?[0-9]+)?$");
    private static final Splitter SPLITTER = Splitter.on(":").trimResults();
    @Override
    public Int2DoubleMap apply(Readable readable) {
        Scanner scanner = new Scanner(readable);
        scanner.useDelimiter("\n");

        Int2DoubleOpenHashMap weights = new Int2DoubleOpenHashMap();

        boolean weightsStarted = false;
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            Matcher m = WEIGHT_PATTERN.matcher(line);
            if (m.matches()) {
                List<String> split = ImmutableList.copyOf(SPLITTER.split(line));
                checkState(split.size() == 2);
                int featureHash = Integer.parseInt(split.get(0));
                double weight = Double.parseDouble(split.get(1));
                weights.put(featureHash, weight);
                weightsStarted = true;
            } else if (weightsStarted) {
                throw new IllegalStateException("Weight section had started, but line did not match weight pattern!" +
                        " Offending line:" + line);
            }
        }
        checkState(weights.size() > 0, "Suspicious empty weights!");
        weights.trim();
        return weights;
    }
}
