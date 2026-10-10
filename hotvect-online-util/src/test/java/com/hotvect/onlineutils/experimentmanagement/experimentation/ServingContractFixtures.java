package com.hotvect.onlineutils.experimentmanagement.experimentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.onlineutils.serving.ServingSlot;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Serving contract fixtures shared by the experimentation tests.
 *
 * <p>Refreshing a slot runs the real serving contract validation against the algorithm
 * factory named in the algorithm definition, so these tests need factories that genuinely declare
 * matching and mismatching types.
 */
final class ServingContractFixtures {
    private ServingContractFixtures() {
    }

    /** The contract every test slot declares. */
    static ServingSlot servingSlot(final String name) {
        return ServingSlot.builder(name, new TypeToken<Ranker<Object, Object>>() {})
                .touchpoints(Set.of(name + "-touchpoint"))
                .build();
    }

    /** Declares the {@code Ranker<Object, Object>} contract that {@link #servingSlot} accepts. */
    public static final class CompatibleRankerFactory implements SimpleRankerFactory<Object, Object> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<Object, Object> apply(final Optional<JsonNode> hyperparameter) {
            return new TestAlgorithm();
        }
    }

    /** Declares a shared type that {@link #servingSlot} does not accept. */
    public static final class MismatchedRankerFactory implements SimpleRankerFactory<String, Object> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, Object> apply(final Optional<JsonNode> hyperparameter) {
            return rankingRequest -> RankingResponse.newResponse(List.of());
        }
    }

    static final class TestAlgorithm implements Ranker<Object, Object> {
        @Override
        public RankingResponse<Object> rank(final RankingRequest<Object, Object> rankingRequest) {
            return RankingResponse.newResponse(List.of());
        }
    }
}
