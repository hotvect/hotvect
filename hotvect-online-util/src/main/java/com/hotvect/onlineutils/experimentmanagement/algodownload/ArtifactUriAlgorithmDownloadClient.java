package com.hotvect.onlineutils.experimentmanagement.algodownload;

import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Downloads immutable algorithm artifacts from either S3 or local file URIs. */
public final class ArtifactUriAlgorithmDownloadClient implements AlgorithmDownloadClient {
    private S3AlgorithmDownloadClient s3;

    @Override
    public void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination) {
        download(algorithm.absoluteS3AlgorithmJarPath(), destination);
    }

    @Override
    public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
        download(algorithm.absoluteS3AlgorithmParameterPath(), destination);
    }

    private void download(String source, Path destination) {
        URI uri = URI.create(source);
        switch (uri.getScheme()) {
            case "file" -> copy(Path.of(uri), destination);
            case "s3" -> s3().downloadFile(destination, source);
            case null -> throw new IllegalArgumentException("Artifact URI must include a scheme: " + source);
            default -> throw new IllegalArgumentException("Unsupported algorithm artifact URI scheme: " + uri.getScheme());
        }
    }

    private static void copy(Path source, Path destination) {
        try {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new RuntimeException("Could not copy algorithm artifact " + source, error);
        }
    }

    private synchronized S3AlgorithmDownloadClient s3() {
        if (s3 == null) {
            s3 = new S3AlgorithmDownloadClient();
        }
        return s3;
    }

    @Override
    public synchronized void close() {
        if (s3 != null) {
            s3.close();
        }
    }
}
