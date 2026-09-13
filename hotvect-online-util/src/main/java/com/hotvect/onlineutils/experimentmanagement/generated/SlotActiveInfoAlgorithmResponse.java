// Generated EMS client binding. Do not edit manually.


package com.hotvect.onlineutils.experimentmanagement.generated;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** Wire model generated from the pinned direct Stage 2 EMS contract. */
public record SlotActiveInfoAlgorithmResponse(
        @JsonProperty(value = "algorithm_name", required = true) String algorithmName,
        @JsonProperty(value = "algorithm_version", required = true) String algorithmVersion,
        @JsonProperty(value = "latest_algorithm_parameter") String latestAlgorithmParameter,
        @JsonProperty(value = "absolute_s3_algorithm_jar_path", required = true) String absoluteS3AlgorithmJarPath,
        @JsonProperty(value = "absolute_s3_algorithm_parameter_path") String absoluteS3AlgorithmParameterPath
) {
    public SlotActiveInfoAlgorithmResponse {
        algorithmName = Objects.requireNonNull(algorithmName, "algorithmName must not be null");
        algorithmVersion = Objects.requireNonNull(algorithmVersion, "algorithmVersion must not be null");
        absoluteS3AlgorithmJarPath = Objects.requireNonNull(absoluteS3AlgorithmJarPath, "absoluteS3AlgorithmJarPath must not be null");
    }
}
