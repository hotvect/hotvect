package com.hotvect.offlineutils.util;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class S3StreamingDownloaderTest {

    private static final String BUCKET_NAME = "noaa-global-hourly-pds"; // NOAA GOES-16 bucket, for example
    private static final String PUBLIC_FILE_KEY = "2024/A0001553129.csv"; // Example key

    @Test
    void testStreamPublicDataset() {
        S3Client s3Client = S3Client.builder()
                .region(Region.US_EAST_1) // Adjust the region as necessary
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .build();

        // Instantiate your downloader with the S3 client
        S3StreamingDownloader downloader = new S3StreamingDownloader(s3Client);

        // Stream and verify the content from the public dataset
        try (Stream<String> lines = downloader.stream(BUCKET_NAME, PUBLIC_FILE_KEY).limit(10)) {
            System.out.println(lines.collect(Collectors.toList()));

            // Assert that the stream is not empty

        }
        // Assert that the stream is not emp
        s3Client.close();
    }
}
