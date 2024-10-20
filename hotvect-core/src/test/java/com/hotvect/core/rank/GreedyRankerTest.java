//package com.hotvect.core.rank;
//
//import com.google.common.hash.Hashing;
//import com.hotvect.api.data.SparseVector;
//import com.hotvect.api.data.ranking.RankingDecision;
//import com.hotvect.api.data.ranking.RankingRequest;
//import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
//import com.hotvect.core.score.Estimator;
//import net.jqwik.api.*;
//import org.junit.jupiter.api.Assertions;
//
//import java.util.List;
//import java.util.Objects;
//import java.util.function.ToIntFunction;
//import java.util.stream.Collectors;
//
//import static java.util.stream.Collectors.toList;
//
//class GreedyRankerTest {
//    private final ToIntFunction<String> toMockFeatureFunction = s -> {
//        if (s != null) {
//            return Hashing.murmur3_32_fixed().hashUnencodedChars(s).asInt();
//        } else {
//            return 0;
//        }
//    };
//    private final RankingVectorizer<String, String> mockVectorizer = rankingRequest -> rankingRequest.getActions().stream()
//            .map(x -> new SparseVector(new int[]{toMockFeatureFunction.applyAsInt(x)})
//            ).collect(Collectors.toList());
//
//    private final Estimator mockEstimator = featureVector -> featureVector.getCategoricalIndices()[0] % 10;
//
//    @Property(afterFailure = AfterFailureMode.SAMPLE_ONLY)
//    void apply(@ForAll("requests") RankingRequest<String, String> request) {
//
//        GreedyRanker<String, String> greedyRanker = new GreedyRanker<>(mockVectorizer, mockEstimator);
//
//        List<RankingDecision<String>> actual = greedyRanker.rank(request).getRankingDecisions();
//
//        actual_scores_are_in_reverse_natural_order(actual);
//        skuId_to_score_mapping_is_correct(request.getAvailableActions(), actual);
//
//
//    }
//
//    private void skuId_to_score_mapping_is_correct(List<String> availableActions, List<RankingDecision<String>> actual) {
//        availableActions.forEach(ca -> {
//            var correspondingRankingDecision = actual.stream().filter(rd -> Objects.equals(rd.getAction(), ca)).collect(toList());
//            correspondingRankingDecision.forEach(rd -> {
//                Assertions.assertEquals(toMockFeatureFunction.applyAsInt(ca) % 10, correspondingRankingDecision.get(0).getScore(), 0.000001);
//                Assertions.assertEquals(availableActions.get(rd.getActionIndex()), ca);
//            });
//        });
//
//    }
//
//    private void actual_scores_are_in_reverse_natural_order(List<RankingDecision<String>> actual) {
//        var actualScores = actual.stream().map(RankingDecision::getScore).collect(toList());
//        // Note how the order should be reverse order
//        var expectedScores = actual.stream().map(RankingDecision::getScore).sorted((d1, d2) -> Double.compare(d2, d1)).collect(toList());
//        Assertions.assertEquals(expectedScores, actualScores);
//
//    }
//
//    @Provide("requests")
//    Arbitrary<RankingRequest<String, String>> generateRequestSamples() {
//        var exampleIds = Arbitraries.strings().ofMaxLength(5).injectNull(0.01);
//        var shared = Arbitraries.strings().ofMaxLength(20).injectNull(0.01);
//        var action = Arbitraries.strings().ofMaxLength(40).injectNull(0.01).list().ofMaxSize(30);
//        return Combinators.combine(exampleIds, shared, action).as(RankingRequest::new);
//    }
//
//}