package com.hotvect.catboost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.hotvect.core.transform.ranking.StandardRankingTransformer;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatBoostBulkScorerFactoryTest {

    @Test
    void createMissingModelParameterThrows() {
        @SuppressWarnings("unchecked")
        StandardRankingTransformer<String, String> transformer = mock(StandardRankingTransformer.class);

        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> CatBoostBulkScorerFactory.create(transformer, Map.of(), Optional.empty())
        );
        assertEquals("Missing required parameter: model.parameter", exception.getMessage());
    }
}

