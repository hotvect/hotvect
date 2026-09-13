package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.BulkScorerFactory;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import com.hotvect.utils.HyperparamUtils;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;

public class CatBoostStreamingBulkScorerFactory<SHARED, ACTION>
        implements BulkScorerFactory<StreamingRankingTransformer<SHARED, ACTION>, SHARED, ACTION> {
    private final Function<Map<String, FeatureStoreResponse>, FeatureStoreResponseContainer>
            featureStoreResponseContainerProvider;

    public CatBoostStreamingBulkScorerFactory() {
        this(_responses -> FeatureStoreResponseContainer.empty());
    }

    /**
     * Creates a CatBoost factory that converts the responses collected during feature transformation
     * into the container returned with the scoring result.
     *
     * @param featureStoreResponseContainerProvider converter for the responses collected during transformation
     */
    protected CatBoostStreamingBulkScorerFactory(
            Function<Map<String, FeatureStoreResponse>, FeatureStoreResponseContainer>
                    featureStoreResponseContainerProvider
    ) {
        this.featureStoreResponseContainerProvider = Objects.requireNonNull(
                featureStoreResponseContainerProvider,
                "featureStoreResponseContainerProvider cannot be null"
        );
    }

    @Override
    public BulkScorer<SHARED, ACTION> apply(
            StreamingRankingTransformer<SHARED, ACTION> rankingTransformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter
    ) {
        InputStream modelStream = CatBoostFactoryUtils.getModelStream(parameters);
        checkState(
                modelStream != null,
                "Missing CatBoost model parameters. Expected key '%s' (preferred) or '%s' (legacy). Available keys: %s",
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V10,
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V9,
                parameters.keySet()
        );
        HotvectCatBoostModel hotvectCatBoostModel = HotvectCatBoostModel.loadModel(modelStream);
        String taskType = CatBoostFactoryUtils.getTaskType(hyperparameter);
        boolean parallelBatchScoring = HyperparamUtils.getOrDefault(
                hyperparameter,
                JsonNode::asBoolean,
                true,
                "parallel_batch_scoring"
        );

        return new CatBoostStreamingBulkScorer<>(
                rankingTransformer,
                hotvectCatBoostModel,
                taskType,
                parallelBatchScoring,
                featureStoreResponseContainerProvider
        );
    }
}
