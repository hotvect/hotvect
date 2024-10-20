package com.hotvect.core.util;

import com.hotvect.utils.FuzzyMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FuzzyMatchTest {

    private FuzzyMatch fuzzyMatch;

    @BeforeEach
    public void setUp() {
        Set<String> candidates = new HashSet<>();
        candidates.add("african_elephant");
        candidates.add("african_2elephant");
        candidates.add("bengal_tiger");
        candidates.add("blue_whale");
        candidates.add("grizzly_bear");
        candidates.add("peregrine_falcon");
        candidates.add("african_lion");
        candidates.add("asian_elephant");
        fuzzyMatch = new FuzzyMatch(candidates);
    }

    private static Stream<Arguments> provideStringsForTest() {
        return Stream.of(
                Arguments.of("african_eleph", List.of("african_elephant", "african_2elephant")),
                Arguments.of("blue_wha", List.of("blue_whale", "asian_elephant")),
                Arguments.of("grz_bear", List.of("grizzly_bear", "bengal_tiger")),
                Arguments.of("pregrine_facon", List.of("peregrine_falcon", "african_lion")),
                Arguments.of("afrcn_elephnt", List.of("african_elephant", "african_2elephant"))
        );
    }

    @ParameterizedTest
    @MethodSource("provideStringsForTest")
    public void testGetClosestCandidates(String query, List<String> expected) {
        List<String> actual = fuzzyMatch.getClosestCandidates(query);
        assertEquals(expected, actual);
    }
}
