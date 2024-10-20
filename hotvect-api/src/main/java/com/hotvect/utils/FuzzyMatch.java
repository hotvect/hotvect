package com.hotvect.utils;

import me.xdrop.fuzzywuzzy.FuzzySearch;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FuzzyMatch {
    private final Set<String> candidates;

    public FuzzyMatch(Set<String> candidates) {
        this.candidates = candidates;
    }

    public List<String> getClosestCandidates(String query) {
        // Using FuzzySearch's ratio method for matching
        // You can also explore other methods like partialRatio, tokenSortRatio, etc.
        return candidates.stream()
                .map(candidate -> new CandidateScore(candidate, FuzzySearch.ratio(query.toLowerCase(), candidate.toLowerCase())))
                .sorted((a, b) -> Integer.compare(b.score, a.score)) // Sort in descending order of score
                .limit(2) // Limit to top 3 matches
                .map(candidateScore -> candidateScore.candidate)
                .collect(Collectors.toList());
    }

    // Helper class to hold candidate and score
    private static class CandidateScore {
        String candidate;
        int score;

        CandidateScore(String candidate, int score) {
            this.candidate = candidate;
            this.score = score;
        }
    }
}
