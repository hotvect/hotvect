package com.hotvect.onlineutils.experimentmanagement.algodownload;

import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;

import java.nio.file.Path;

public interface AlgorithmDownloadClient {
    void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination);
    void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination);
}
