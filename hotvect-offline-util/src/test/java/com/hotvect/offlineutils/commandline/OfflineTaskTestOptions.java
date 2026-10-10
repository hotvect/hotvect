package com.hotvect.offlineutils.commandline;

import java.io.File;
import java.util.List;

final class OfflineTaskTestOptions {
    private OfflineTaskTestOptions() {
    }

    static Options direct() {
        return direct(null);
    }

    static Options direct(File parameters) {
        Options options = new Options();
        options.algorithmSource = directSource(parameters);
        return options;
    }

    private static OfflineAlgorithmSource.Direct directSource(File parameters) {
        return new OfflineAlgorithmSource.Direct(
                new File("test-algorithm.jar"),
                "test-algorithm",
                List.of(),
                List.of(),
                parameters);
    }
}
