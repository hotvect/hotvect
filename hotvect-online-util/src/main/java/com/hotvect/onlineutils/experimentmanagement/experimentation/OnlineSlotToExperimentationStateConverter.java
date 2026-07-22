package com.hotvect.onlineutils.experimentmanagement.experimentation;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmRepository;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentConfiguration;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

public class OnlineSlotToExperimentationStateConverter {
    private static final Comparator<Experiment> BY_EXPERIMENT_ID = Comparator.comparingInt(Experiment::experimentId);
    private static final Comparator<Variant> BY_VARIANT_ID = Comparator.comparingInt(Variant::variantId);

    private final AlgorithmRepository algorithmRepository;

    public OnlineSlotToExperimentationStateConverter(final AlgorithmRepository algorithmRepository) {
        this.algorithmRepository = algorithmRepository;
    }

    public ExperimentationState convert(final Slot slot) throws MalformedAlgorithmException {
        final Variant defaultVariant = slot.defaultVariant();
        final int totalNumberOfShards = slot.totalNumberOfShards();
        final List<Experiment> experiments = new ArrayList<>(slot.experiments());
        experiments.sort(BY_EXPERIMENT_ID);

        // Obtain new algorithm parameter metadata
        final Map<AlgorithmId, AlgorithmMetadata> requiredAlgorithms = extractRequiredAlgorithms(experiments);
        putRequiredAlgorithm(requiredAlgorithms, defaultVariant.algorithm());

        final Map<AlgorithmId, AlgorithmInstance<?>> algorithmInstancesById =
                loadAlgorithmInstances(requiredAlgorithms);

        final Map<Integer, ExperimentConfiguration> experimentId2ExperimentConfig =
                new HashMap<>(experiments.size());
        for (final Experiment experiment : experiments) {
            final List<Variant> sortedVariants = new ArrayList<>(experiment.variants());
            sortedVariants.sort(BY_VARIANT_ID);

            final List<VariantConfiguration> variantConfigurations = new ArrayList<>(sortedVariants.size());
            for (final Variant variant : sortedVariants) {
                variantConfigurations.add(buildVariantConfigurationFromVariant(variant, algorithmInstancesById));
            }

            final ExperimentConfiguration previous = experimentId2ExperimentConfig.put(
                    experiment.experimentId(),
                    new ExperimentConfiguration(experiment, variantConfigurations));
            checkState(previous == null, "Duplicate experiment id %s", experiment.experimentId());
        }
        final Map<Integer, VariantConfiguration> variantIdToVariantConfiguration =
                extractVariantsFromExperiments(experimentId2ExperimentConfig.values());
        final VariantConfiguration defaultVariantConfiguration =
                Objects.requireNonNull(buildVariantConfigurationFromVariant(defaultVariant, algorithmInstancesById));
        final VariantConfiguration previousDefaultVariantConfiguration =
                variantIdToVariantConfiguration.putIfAbsent(defaultVariant.variantId(), defaultVariantConfiguration);
        checkState(previousDefaultVariantConfiguration == null,
                "Duplicate variant id %s",
                defaultVariant.variantId());

        final Map<Integer, ExperimentConfiguration> shardId2ExperimentConfig =
                buildShardIdToExperimentConfiguration(experiments, experimentId2ExperimentConfig, totalNumberOfShards);

        final Map<String, Integer> userForcedAssignments =
                buildUserForcedAssignments(slot.userForcedAssignments());

        return new ExperimentationState(
                extractMaxVariantId(experiments, defaultVariant),
                ImmutableMap.copyOf(shardId2ExperimentConfig),
                ImmutableMap.copyOf(variantIdToVariantConfiguration),
                ImmutableMap.copyOf(userForcedAssignments),
                slot.slotSalt(),
                defaultVariantConfiguration
        );
    }

    @VisibleForTesting
    Map<AlgorithmId, AlgorithmMetadata> extractRequiredAlgorithms(final List<Experiment> experiments) {
        final Map<AlgorithmId, AlgorithmMetadata> requiredAlgorithms = new HashMap<>();
        for (final Experiment experiment : experiments) {
            for (final Variant variant : experiment.variants()) {
                putRequiredAlgorithm(requiredAlgorithms, variant.algorithm());
            }
        }
        return requiredAlgorithms;
    }

    private static void putRequiredAlgorithm(
            final Map<AlgorithmId, AlgorithmMetadata> requiredAlgorithms,
            final AlgorithmMetadata algorithmMetadata) {
        final AlgorithmMetadata previous = requiredAlgorithms.putIfAbsent(
                algorithmMetadata.algorithmId(),
                algorithmMetadata);
        if (previous != null) {
            checkState(previous.equals(algorithmMetadata),
                    "Unmatched algorithm metadata found:%s, %s",
                    previous,
                    algorithmMetadata);
        }
    }

    private Map<AlgorithmId, AlgorithmInstance<?>> loadAlgorithmInstances(
            final Map<AlgorithmId, AlgorithmMetadata> requiredAlgorithms) {
        final Map<AlgorithmId, AlgorithmInstance<?>> algorithmInstances = new HashMap<>();
        for (final Entry<AlgorithmId, AlgorithmMetadata> algorithm : requiredAlgorithms.entrySet()) {
            algorithmInstances.put(algorithm.getKey(), algorithmRepository.getAlgorithmInstance(algorithm.getValue()));
        }
        return algorithmInstances;
    }

    private static Map<Integer, VariantConfiguration> extractVariantsFromExperiments(
            final Iterable<ExperimentConfiguration> experiments) {
        Map<Integer, VariantConfiguration> variantIdToVariantConfiguration = new HashMap<>();
        for (ExperimentConfiguration experimentConfiguration : experiments) {
            for (VariantConfiguration variant : experimentConfiguration.variants()) {
                final VariantConfiguration previous = variantIdToVariantConfiguration.putIfAbsent(
                        variant.variant().variantId(),
                        variant);
                checkState(previous == null,
                        "Duplicate variant id %s",
                        variant.variant().variantId());
            }
        }
        return variantIdToVariantConfiguration;
    }

    private static Map<String, Integer> buildUserForcedAssignments(
            final List<UserForcedAssignment> userForcedAssignments) {
        final Map<String, Integer> forcedAssignmentsByUser = new HashMap<>(userForcedAssignments.size());
        for (final UserForcedAssignment userForcedAssignment : userForcedAssignments) {
            final Integer previous = forcedAssignmentsByUser.put(
                    userForcedAssignment.userId(),
                    userForcedAssignment.variantId());
            checkState(previous == null,
                    "Duplicate user forced assignment for user %s",
                    userForcedAssignment.userId());
        }
        return forcedAssignmentsByUser;
    }

    private static Map<Integer, ExperimentConfiguration> buildShardIdToExperimentConfiguration(
            final List<Experiment> experiments,
            final Map<Integer, ExperimentConfiguration> experimentId2ExperimentConfig,
            final int totalNumberOfShards) {
        final Map<Integer, ExperimentConfiguration> shardId2ExperimentConfig = new HashMap<>(totalNumberOfShards);
        for (final Experiment experiment : experiments) {
            final ExperimentConfiguration experimentConfiguration =
                    experimentId2ExperimentConfig.get(experiment.experimentId());
            final List<Integer> sortedShards = new ArrayList<>(experiment.shards().stream()
                    .map(Shard::shardId)
                    .toList());
            sortedShards.sort(Integer::compareTo);
            for (final Integer shardId : sortedShards) {
                checkState(
                        shardId >= 1 && shardId <= totalNumberOfShards,
                        "Experiment %s shard %s must be within [1, %s]",
                        experiment.experimentId(),
                        shardId,
                        totalNumberOfShards);
                final ExperimentConfiguration previous =
                        shardId2ExperimentConfig.put(shardId, experimentConfiguration);
                if (previous != null) {
                    throw new IllegalStateException(String.format(
                            "Shard %s is assigned to multiple experiments: %s and %s",
                            shardId,
                            previous.experiment().experimentId(),
                            experiment.experimentId()));
                }
            }
        }
        return shardId2ExperimentConfig;
    }

    private int extractMaxVariantId(final List<Experiment> experiments, final Variant defaultVariant) {
        final int defaultVariantId = defaultVariant.variantId();
        return max(experiments.stream()
                .flatMap(experiment -> experiment.variants().stream())
                .mapToInt(Variant::variantId).max().orElse(defaultVariantId), defaultVariantId);
    }

    private VariantConfiguration buildVariantConfigurationFromVariant(
            final Variant variant,
            final Map<AlgorithmId, AlgorithmInstance<?>> algorithmInstancesById) {
        final AlgorithmInstance<?> algorithmInstance = Objects.requireNonNull(
                algorithmInstancesById.get(variant.algorithm().algorithmId()),
                "Missing algorithm instance for " + variant.algorithm().algorithmId()
                        + " parameter " + variant.algorithm().latestAlgorithmParameter());
        return new VariantConfiguration(
                variant,
                algorithmInstance
        );
    }
}
