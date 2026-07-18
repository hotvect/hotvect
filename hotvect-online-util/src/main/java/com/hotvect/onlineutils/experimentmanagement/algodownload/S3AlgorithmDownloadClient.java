package com.hotvect.onlineutils.experimentmanagement.algodownload;

import com.google.common.base.Preconditions;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Uri;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

public class S3AlgorithmDownloadClient implements AlgorithmDownloadClient, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(S3AlgorithmDownloadClient.class);

    private final S3AsyncClient s3Client;
    private final boolean closeS3ClientOnClose;

    public S3AlgorithmDownloadClient() {
        this(createDefaultS3Client(), true);
    }

    public S3AlgorithmDownloadClient(final S3AsyncClient s3Client) {
        this(s3Client, false);
    }

    S3AlgorithmDownloadClient(final S3AsyncClient s3Client, final boolean closeS3ClientOnClose) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.closeS3ClientOnClose = closeS3ClientOnClose;
    }

    @Override
    public void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination) {
        downloadFile(destination, algorithm.absoluteS3AlgorithmJarPath());
    }

    @Override
    public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
        downloadFile(destination, algorithm.absoluteS3AlgorithmParameterPath());
    }

    protected void downloadFile(Path destination, String s3Uri) {
        try {
            S3Uri sourceS3Uri = s3Client.utilities().parseUri(URI.create(s3Uri));
            String bucket = sourceS3Uri.bucket()
                .orElseThrow(() -> new IllegalArgumentException("Unable to get bucket from: " + s3Uri));
            String key = sourceS3Uri.key()
                .orElseThrow(() -> new IllegalArgumentException("Unable to get key from: " + s3Uri));

            // Ensure the destination directory exists
            Path parentDir = destination.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

            SdkHttpResponse response = s3Client.getObject(getObjectRequest, AsyncResponseTransformer.toFile(destination))
                .get()
                .sdkHttpResponse();

            Preconditions.checkState(response.isSuccessful(), "File download failed: [%s] %s",
                response.statusCode(),
                response.statusText());

            LOG.info("File {} from s3 downloaded to path {}", s3Uri, destination);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("File from S3 couldn't get downloaded : " + s3Uri, e);
        } catch (ExecutionException | IOException e) {
            throw new RuntimeException("File from S3 couldn't get downloaded : " + s3Uri, e);
        }
    }

    @Override
    public void close() {
        if (closeS3ClientOnClose) {
            s3Client.close();
        }
    }

    private static S3AsyncClient createDefaultS3Client() {
        return S3AsyncClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.create())
            .region(Region.EU_CENTRAL_1)
            .build();
    }
}
