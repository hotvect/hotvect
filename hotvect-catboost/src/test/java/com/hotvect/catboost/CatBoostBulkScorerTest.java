package com.hotvect.catboost;

import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.core.transform.Computable;
import com.hotvect.core.transform.ranking.ComputingCandidate;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatBoostBulkScorerTest {
    private enum TestNamespace implements Namespace {
        NUM;

        @Override
        public String getName() {
            return name();
        }

        @Override
        public com.hotvect.api.data.ValueType getFeatureValueType() {
            return CatBoostFeatureType.NUMERICAL;
        }
    }

    @Test
    void scoreWithForkJoinKeepsActionsAligned() {
        ComputingRankingTransformer<String, String> transformer = mock(ComputingRankingTransformer.class);
        HotvectCatBoostModel model = mock(HotvectCatBoostModel.class);

        SortedSet<Namespace> usedFeatures = new TreeSet<>(Namespace.alphabetical());
        usedFeatures.add(TestNamespace.NUM);
        when(transformer.getUsedFeatures()).thenReturn(usedFeatures);

        Map<String, Double> numericByAction = Map.of(
                "a1", 1.0,
                "a2", 2.0,
                "a3", 3.0,
                "a4", 4.0
        );

        List<ComputingCandidate<String, String>> candidates = List.of(
                mockCandidate("a1"),
                mockCandidate("a2"),
                mockCandidate("a3"),
                mockCandidate("a4")
        );

        RankingRequest<String, String> rankingRequest = new RankingRequest<>("example", "shared", List.of("a1", "a2", "a3", "a4"));
        ComputingRankingRequest<String, String> computingRequest = new ComputingRankingRequest<>(rankingRequest, null, candidates);

        when(transformer.prepare(any(RankingRequest.class))).thenReturn(computingRequest);

        when(transformer.transform(any(ComputingRankingRequest.class))).thenAnswer(invocation -> {
            ComputingRankingRequest<String, String> req = invocation.getArgument(0);
            return req.candidates().stream().map(candidate -> {
                String action = candidate.getAction().getOriginalInput();
                NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                record.put(TestNamespace.NUM, numericByAction.get(action));
                return TransformedAction.of(action, record);
            }).toList();
        });

        when(model.predict(any(float[][].class), any(String[][].class), any(String[][].class), any(float[][][].class)))
                .thenAnswer(invocation -> {
                    float[][] numericFeatures = invocation.getArgument(0);
                    DoubleList ret = new DoubleArrayList(numericFeatures.length);
                    for (float[] row : numericFeatures) {
                        ret.add(row[0]);
                    }
                    return ret;
                });

        CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(transformer, model, 1, "regression");

        BulkScoreResponse<String> response = scorer.score(rankingRequest);

        assertEquals(4, response.decisions().size());
        assertEquals("a1", response.decisions().get(0).action());
        assertEquals(1.0, response.decisions().get(0).score());
        assertEquals("a2", response.decisions().get(1).action());
        assertEquals(2.0, response.decisions().get(1).score());
        assertEquals("a3", response.decisions().get(2).action());
        assertEquals(3.0, response.decisions().get(2).score());
        assertEquals("a4", response.decisions().get(3).action());
        assertEquals(4.0, response.decisions().get(3).score());

        verify(transformer, times(4)).transform(any(ComputingRankingRequest.class));
    }

    @Test
    void scoreReturnsProvidedFeatureStoreResponseContainer() {
        ComputingRankingTransformer<String, String> transformer = mock(ComputingRankingTransformer.class);
        HotvectCatBoostModel model = mock(HotvectCatBoostModel.class);

        SortedSet<Namespace> usedFeatures = new TreeSet<>(Namespace.alphabetical());
        usedFeatures.add(TestNamespace.NUM);
        when(transformer.getUsedFeatures()).thenReturn(usedFeatures);

        List<ComputingCandidate<String, String>> candidates = List.of(
                mockCandidate("a1"),
                mockCandidate("a2")
        );

        RankingRequest<String, String> rankingRequest = new RankingRequest<>("example", "shared", List.of("a1", "a2"));
        ComputingRankingRequest<String, String> computingRequest = new ComputingRankingRequest<>(rankingRequest, null, candidates);

        when(transformer.prepare(any(RankingRequest.class))).thenReturn(computingRequest);
        when(transformer.transform(any(ComputingRankingRequest.class))).thenAnswer(invocation -> {
            ComputingRankingRequest<String, String> req = invocation.getArgument(0);
            return req.candidates().stream().map(candidate -> {
                String action = candidate.getAction().getOriginalInput();
                NamespacedRecordImpl<Namespace, Object> record = new NamespacedRecordImpl<>();
                record.put(TestNamespace.NUM, action.equals("a1") ? 1.0 : 2.0);
                return TransformedAction.of(action, record);
            }).toList();
        });

        when(model.predict(any(float[][].class), any(String[][].class), any(String[][].class), any(float[][][].class)))
                .thenAnswer(invocation -> {
                    float[][] numericFeatures = invocation.getArgument(0);
                    DoubleList ret = new DoubleArrayList(numericFeatures.length);
                    for (float[] row : numericFeatures) {
                        ret.add(row[0]);
                    }
                    return ret;
                });

        FeatureStoreResponseContainer featureStoreResponseContainer = new FeatureStoreResponseContainer(
                Map.of("customer_features_legacy", SimpleFeatureStoreResponse.success(Map.of()))
        );

        CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(
                transformer,
                model,
                10,
                "regression",
                _request -> featureStoreResponseContainer
        );

        BulkScoreResponse<String> response = scorer.score(rankingRequest);
        assertEquals(featureStoreResponseContainer, response.featureStoreResponseContainer());
    }

    @Test
    void bulkScoreRankingRequestIsUnsupported() {
        ComputingRankingTransformer<String, String> transformer = mock(ComputingRankingTransformer.class);
        HotvectCatBoostModel model = mock(HotvectCatBoostModel.class);
        SortedSet<Namespace> usedFeatures = new TreeSet<>(Namespace.alphabetical());
        usedFeatures.add(TestNamespace.NUM);
        when(transformer.getUsedFeatures()).thenReturn(usedFeatures);

        CatBoostBulkScorer<String, String> scorer = new CatBoostBulkScorer<>(transformer, model, 1, "regression");
        RankingRequest<String, String> rankingRequest = new RankingRequest<>("example", "shared", List.of("a1"));

        assertThrows(UnsupportedOperationException.class, () -> scorer.bulkScore(rankingRequest));
    }

    @SuppressWarnings("unchecked")
    private static <SHARED, ACTION> ComputingCandidate<SHARED, ACTION> mockCandidate(ACTION action) {
        ComputingCandidate<SHARED, ACTION> candidate = mock(ComputingCandidate.class);
        Computable<ACTION> computableAction = mock(Computable.class);
        when(computableAction.getOriginalInput()).thenReturn(action);
        when(candidate.getAction()).thenReturn(computableAction);
        return candidate;
    }
}
