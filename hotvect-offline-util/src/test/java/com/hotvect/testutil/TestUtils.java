package com.hotvect.testutil;

import java.util.*;
import java.util.stream.Stream;

public class TestUtils {
    public static Stream<Map<String, String>> testRecords() {
        Random rng = new Random(0);
        List<String> categoricals = new ArrayList<>();
        categoricals.add("");
        categoricals.add("a");

        List<String> numericals = new ArrayList<>();
        numericals.add("0.0");
        numericals.add("-1");

        return Stream.iterate(1, i -> i++).map(i -> {
            Map<String, String> r = new HashMap<>();
            r.put("feature1", categoricals.get(rng.nextInt(categoricals.size())));
            r.put("feature2", categoricals.get(rng.nextInt(categoricals.size())));
            r.put("feature3", numericals.get(rng.nextInt(categoricals.size())));
            return r;
        });


    }
}
