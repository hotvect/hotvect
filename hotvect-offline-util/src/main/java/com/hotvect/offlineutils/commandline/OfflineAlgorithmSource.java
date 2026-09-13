package com.hotvect.offlineutils.commandline;

import java.io.File;
import java.util.List;
import java.util.Objects;

/** The mutually exclusive algorithm source selected for one offline process. */
sealed interface OfflineAlgorithmSource
        permits OfflineAlgorithmSource.Direct, OfflineAlgorithmSource.Fixed, OfflineAlgorithmSource.Ems {

    record Direct(
            File algorithmJar,
            String algorithmDefinition,
            List<File> domainModelJars,
            List<File> additionalJars,
            File parameters) implements OfflineAlgorithmSource {
        public Direct {
            algorithmJar = CommandlineUtility.expandTilde(
                    Objects.requireNonNull(algorithmJar, "algorithmJar must not be null"));
            if (algorithmDefinition == null || algorithmDefinition.isBlank()) {
                throw new IllegalArgumentException("algorithmDefinition must not be blank");
            }
            domainModelJars = expandTilde(
                    Objects.requireNonNull(domainModelJars, "domainModelJars must not be null"));
            additionalJars = expandTilde(
                    Objects.requireNonNull(additionalJars, "additionalJars must not be null"));
            parameters = CommandlineUtility.expandTilde(parameters);
        }
    }

    record Fixed(
            File composition,
            List<File> domainModelJars) implements OfflineAlgorithmSource {
        public Fixed {
            composition = CommandlineUtility.expandTilde(
                    Objects.requireNonNull(composition, "composition must not be null"));
            domainModelJars = expandTilde(Objects.requireNonNull(
                    domainModelJars,
                    "domainModelJars must not be null"));
        }
    }

    record Ems(
            String rootSlot,
            File state,
            String assignmentKeyJsonPointer,
            List<File> domainModelJars) implements OfflineAlgorithmSource {
        public Ems {
            if (rootSlot == null || rootSlot.isBlank()) {
                throw new IllegalArgumentException("rootSlot must not be blank");
            }
            state = CommandlineUtility.expandTilde(
                    Objects.requireNonNull(state, "state must not be null"));
            if (assignmentKeyJsonPointer == null || !assignmentKeyJsonPointer.startsWith("/")) {
                throw new IllegalArgumentException(
                        "assignmentKeyJsonPointer must be an RFC 6901 JSON Pointer");
            }
            domainModelJars = expandTilde(Objects.requireNonNull(
                    domainModelJars,
                    "domainModelJars must not be null"));
        }
    }

    private static List<File> expandTilde(List<File> files) {
        return List.copyOf(files).stream()
                .map(CommandlineUtility::expandTilde)
                .toList();
    }
}
