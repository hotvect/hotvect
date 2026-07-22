package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.BulkScorerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.core.transform.ranking.StandardRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import com.hotvect.utils.HyperparamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;

public class CatBoostBulkScorerFactory<SHARED, ACTION> implements BulkScorerFactory<ComputingRankingTransformer<SHARED, ACTION>, SHARED, ACTION> {
    private static final Logger log = LoggerFactory.getLogger(CatBoostBulkScorerFactory.class);

    @Override
    public BulkScorer<SHARED, ACTION> apply(
            ComputingRankingTransformer<SHARED, ACTION> rankingTransformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter
    ) {
        return createInternal(
                rankingTransformer,
                parameters,
                hyperparameter,
                _request -> FeatureStoreResponseContainer.empty()
        );
    }

    /**
     * Backwards-compatibility: legacy algorithms compiled against Hotvect v9 call this static factory method.
     *
     * @deprecated Use the {@link BulkScorerFactory#apply(Object, Map, Optional)} implementation path.
     */
    @Deprecated(forRemoval = true)
    public static <SHARED, ACTION> BulkScorer<SHARED, ACTION> create(
            StandardRankingTransformer<SHARED, ACTION> rankingTransformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter
    ) {
        return create(
                rankingTransformer,
                parameters,
                hyperparameter,
                _request -> FeatureStoreResponseContainer.empty()
        );
    }

    /**
     * Backwards-compatibility: legacy algorithms compiled against Hotvect v9 call this static factory method.
     *
     * @deprecated Use the {@link BulkScorerFactory#apply(Object, Map, Optional)} implementation path.
     */
    @Deprecated(forRemoval = true)
    public static <SHARED, ACTION> BulkScorer<SHARED, ACTION> create(
            StandardRankingTransformer<SHARED, ACTION> rankingTransformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter,
            Function<ComputingRankingRequest<SHARED, ACTION>, FeatureStoreResponseContainer> featureStoreResponseContainerProvider
    ) {
        return createInternal(rankingTransformer, parameters, hyperparameter, featureStoreResponseContainerProvider);
    }

    private static <SHARED, ACTION> BulkScorer<SHARED, ACTION> createInternal(
            ComputingRankingTransformer<SHARED, ACTION> rankingTransformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter,
            Function<ComputingRankingRequest<SHARED, ACTION>, FeatureStoreResponseContainer> featureStoreResponseContainerProvider
    ) {
        InputStream modelStream = CatBoostFactoryUtils.getModelStream(parameters);
        checkState(
                modelStream != null,
                "Missing CatBoost model parameters. Expected key '%s' or '%s'. Available keys: %s",
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V10,
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V9,
                parameters.keySet()
        );

        HotvectCatBoostModel hotvectCatBoostModel = HotvectCatBoostModel.loadModel(modelStream);
        int noforkThreshold = HyperparamUtils.getOrDefault(
                hyperparameter,
                JsonNode::asInt,
                100,
                "catboost_scorer",
                "nofork_threshold"
        );
        String taskType = CatBoostFactoryUtils.getTaskType(hyperparameter);
        log.info("Using nofork threshold of {}", noforkThreshold);

        return new CatBoostBulkScorer<>(
                rankingTransformer,
                hotvectCatBoostModel,
                noforkThreshold,
                taskType,
                featureStoreResponseContainerProvider
        );
    }
}
