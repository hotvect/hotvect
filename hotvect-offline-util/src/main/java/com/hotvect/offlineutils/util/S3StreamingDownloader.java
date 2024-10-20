package com.hotvect.offlineutils.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class S3StreamingDownloader {
    private static final Logger log = LoggerFactory.getLogger(S3StreamingDownloader.class);

    private final S3Client s3Client;

    public S3StreamingDownloader(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public Stream<String> stream(String bucket, String key) {
        List<S3Object> objects = listObjects(bucket, key);
        List<S3Object> sortedObjects = sortObjectsByKey(objects);

        if (sortedObjects.isEmpty()) {
            throw new NoSuchElementException(String.format("No objects found in S3 bucket:%s, key:%s", bucket, key));
        }

        return sortedObjects.stream()
                .flatMap(s3Object -> {
                    try {
                        return downloadObjectAsStream(bucket, s3Object);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Error streaming object from S3", e);
                    }
                });
    }

    private List<S3Object> listObjects(String bucket, String key) {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(key)
                .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);

        return listResponse.contents();
    }

    private List<S3Object> sortObjectsByKey(List<S3Object> objects) {
        return objects.stream()
                .sorted(Comparator.comparing(S3Object::key))
                .collect(Collectors.toList());
    }

    private Stream<String> downloadObjectAsStream(String bucket, S3Object object) throws IOException {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(object.key())
                .build();

        ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getRequest);
        InputStream inputStream = getInputStream(s3Object, object.key());
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        return reader.lines().onClose(() -> {
            try {
                reader.close();
                log.debug("Closed reader for object: {}", object.key());

            } catch (IOException e) {
                throw new UncheckedIOException("Error closing reader for object:" + object.key(), e);
            }
        });
    }

    private InputStream getInputStream(InputStream is, String key) throws IOException {
        if (key.endsWith(".gz")) {
            return new GZIPInputStream(is);
        } else {
            return is;
        }
    }
}
