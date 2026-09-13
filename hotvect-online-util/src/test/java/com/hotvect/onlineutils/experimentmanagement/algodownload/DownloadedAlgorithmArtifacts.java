package com.hotvect.onlineutils.experimentmanagement.algodownload;

import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import java.nio.file.Path;

/** Test access to downloader-owned artifact construction. */
public final class DownloadedAlgorithmArtifacts {
    private DownloadedAlgorithmArtifacts() {
    }

    public static DownloadedAlgorithmArtifact create(
            AlgorithmInstanceFactory factory,
            Path stagedJar) {
        return new DownloadedAlgorithmArtifact(factory, stagedJar);
    }
}
