package com.hotvect.onlineutils.experimentmanagement.httpclient;

import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfo;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfoAlgorithmResponse;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfoExperimentResponse;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfoShardResponse;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfoUserForcedAssignmentResponse;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfoVariantResponse;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import java.util.Objects;

/** Maps the generated direct Stage 2 EMS wire contract into the online runtime model. */
final class EmsSlotStateMapper {
    private EmsSlotStateMapper() {
    }

    static Slot toSlot(final SlotActiveInfo source) {
        final SlotActiveInfo state = Objects.requireNonNull(source, "source must not be null");
        return new Slot(
                state.slotSalt(),
                state.totalNumberOfShards(),
                toVariant(state.defaultVariant()),
                state.experiments().stream().map(EmsSlotStateMapper::toExperiment).toList(),
                state.userForcedAssignments().stream().map(EmsSlotStateMapper::toUserForcedAssignment).toList());
    }

    private static Experiment toExperiment(final SlotActiveInfoExperimentResponse source) {
        return new Experiment(
                source.experimentId(),
                source.experimentName(),
                source.variants().stream().map(EmsSlotStateMapper::toVariant).toList(),
                source.rampUpPercentage(),
                source.shards().stream().map(EmsSlotStateMapper::toShard).toList());
    }

    private static Variant toVariant(final SlotActiveInfoVariantResponse source) {
        return new Variant(
                source.variantId(),
                toAlgorithmMetadata(source.algorithm()),
                source.createdAt(),
                source.isControl(),
                source.isDefault(),
                source.shardAllocationRatio());
    }

    private static AlgorithmMetadata toAlgorithmMetadata(final SlotActiveInfoAlgorithmResponse source) {
        if ((source.latestAlgorithmParameter() == null)
                != (source.absoluteS3AlgorithmParameterPath() == null)) {
            throw new IllegalArgumentException(
                    "EMS algorithm parameter ID and path must either both be present or both be absent");
        }
        return new AlgorithmMetadata(
                source.algorithmName(),
                source.algorithmVersion(),
                source.latestAlgorithmParameter(),
                source.absoluteS3AlgorithmJarPath(),
                source.absoluteS3AlgorithmParameterPath());
    }

    private static Shard toShard(final SlotActiveInfoShardResponse source) {
        return new Shard(source.shardId(), source.createdAt());
    }

    private static UserForcedAssignment toUserForcedAssignment(final SlotActiveInfoUserForcedAssignmentResponse source) {
        return new UserForcedAssignment(source.userId(), source.variantId());
    }
}
