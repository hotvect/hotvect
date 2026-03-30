package com.hotvect.onlineutils.concurrency.fileutils;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("UnstableApiUsage")
class CpuIntensiveFileAggregatorAvroTest {
    private static final SimpleMeterRegistry mr = new SimpleMeterRegistry();

    private static final Schema SCHEMA = new Schema.Parser().parse(
            "{\"type\":\"record\",\"name\":\"TestRecord\",\"fields\":[" +
            "{\"name\":\"value\",\"type\":\"string\"}" +
            "]}"
    );

    // Aggregation logic for testing with BloomFilter
    private final BiFunction<BloomFilter<String>, GenericRecord, BloomFilter<String>> aggregateBloomFilter =
        (acc, record) -> {
            String value = record.get("value").toString(); // Handle Avro Utf8 objects
            acc.put(value);
            return acc;
        };

    // Aggregation logic for testing with Set
    private final BiFunction<Set<String>, GenericRecord, Set<String>> aggregateSet =
        (acc, record) -> {
            String value = record.get("value").toString(); // Handle Avro Utf8 objects
            acc.add(value);
            return acc;
        };

    @Test
    void testAvroWithoutCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of("apple", "banana", "cherry"),
                        ImmutableList.of("date", "elderberry", "fig")
                ),
                null // No compression
        );

        try {
            final BloomFilter<String> initialFilter = BloomFilter.create(
                    Funnels.stringFunnel(StandardCharsets.UTF_8),
                    1024 * 1024,
                    0.001
            );

            CpuIntensiveFileAggregator<GenericRecord, BloomFilter<String>> testSubject =
                CpuIntensiveFileAggregator.aggregator(
                    mr,
                    source,
                    () -> initialFilter,
                    aggregateBloomFilter
                );

            BloomFilter<String> result = testSubject.call();

            assertNotNull(result);
            assertEquals(initialFilter, result);

            // Test that all values are in the bloom filter
            assertTrue(result.mightContain("apple"));
            assertTrue(result.mightContain("banana"));
            assertTrue(result.mightContain("cherry"));
            assertTrue(result.mightContain("date"));
            assertTrue(result.mightContain("elderberry"));
            assertTrue(result.mightContain("fig"));

            // Test that random values are probably not there
            assertFalse(result.mightContain("xyz123"));
        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithSnappyCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of("value1", "value2", "value3"),
                        ImmutableList.of("value4", "value5", "value6")
                ),
                CodecFactory.snappyCodec()
        );

        try {
            CpuIntensiveFileAggregator<GenericRecord, Set<String>> testSubject =
                CpuIntensiveFileAggregator.aggregator(
                    mr,
                    source,
                    HashSet::new,
                    aggregateSet
                );

            Set<String> result = testSubject.call();

            assertNotNull(result);
            assertEquals(6, result.size());

            Set<String> expected = Set.of("value1", "value2", "value3", "value4", "value5", "value6");
            assertEquals(expected, result);
        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithDeflateCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of("alpha", "beta", "gamma"),
                        ImmutableList.of("delta", "epsilon", "zeta")
                ),
                CodecFactory.deflateCodec(6)
        );

        try {
            // Test with Set-based counting aggregator (thread-safe)
            CpuIntensiveFileAggregator<GenericRecord, Set<String>> testSubject =
                CpuIntensiveFileAggregator.aggregator(
                    mr,
                    source,
                    HashSet::new,
                    (acc, record) -> {
                        String value = record.get("value").toString();
                        acc.add(value);
                        return acc;
                    }
                );

            Set<String> result = testSubject.call();

            assertNotNull(result);
            assertEquals(6, result.size());
        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithFilterAggregation() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of("short", "verylongstring", "mid"),
                        ImmutableList.of("x", "anotherlongstring", "ok")
                ),
                null
        );

        try {
            // Only collect strings longer than 5 characters
            CpuIntensiveFileAggregator<GenericRecord, Set<String>> testSubject =
                CpuIntensiveFileAggregator.aggregator(
                    mr,
                    source,
                    HashSet::new,
                    (acc, record) -> {
                        String value = record.get("value").toString(); // Handle Avro Utf8 objects
                        if (value.length() > 5) {
                            acc.add(value);
                        }
                        return acc;
                    }
                );

            Set<String> result = testSubject.call();

            assertNotNull(result);
            assertEquals(2, result.size());
            assertTrue(result.contains("verylongstring"));
            assertTrue(result.contains("anotherlongstring"));
            assertFalse(result.contains("short"));
            assertFalse(result.contains("x"));
        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void failFastOnError() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of("test1", "test2", "test3")
                ),
                null
        );

        try {
            BiFunction<Set<String>, GenericRecord, Set<String>> badAggregator =
                (acc, record) -> {
                    String value = record.get("value").toString(); // Handle Avro Utf8 objects
                    if (value.equals("test2")) {
                        throw new RuntimeException("Intentional test error");
                    }
                    acc.add(value);
                    return acc;
                };

            CpuIntensiveFileAggregator<GenericRecord, Set<String>> testSubject =
                CpuIntensiveFileAggregator.aggregator(
                    mr,
                    source,
                    HashSet::new,
                    badAggregator
                );

            assertThrows(RuntimeException.class, testSubject::call);
        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithCustomThreadsAndBatchSize() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of("item1", "item2", "item3", "item4", "item5"),
                        ImmutableList.of("item6", "item7", "item8", "item9", "item10")
                ),
                CodecFactory.snappyCodec()
        );

        try {
            CpuIntensiveFileAggregator<GenericRecord, Set<String>> testSubject =
                CpuIntensiveFileAggregator.aggregator(
                    mr,
                    source,
                    HashSet::new,
                    aggregateSet,
                    4, // numThreads
                    20, // queueSize
                    3  // batchSize
                );

            Set<String> result = testSubject.call();

            assertNotNull(result);
            assertEquals(10, result.size());

            for (int i = 1; i <= 10; i++) {
                assertTrue(result.contains("item" + i));
            }
        } finally {
            source.forEach(File::delete);
        }
    }

    /**
     * Creates test Avro files with optional compression.
     *
     * @param fileData list of string lists, each representing one file's data
     * @param codecFactory compression codec (null for no compression)
     * @return list of created temporary files
     */
    private List<File> createTestAvroFiles(List<List<String>> fileData, CodecFactory codecFactory) throws IOException {
        List<File> files = new ArrayList<>();
        for (List<String> data : fileData) {
            File file = createSingleAvroFile(data, codecFactory);
            files.add(file);
        }
        return files;
    }

    /**
     * Creates a single Avro file with the given data and compression.
     */
    private File createSingleAvroFile(List<String> data, CodecFactory codecFactory) throws IOException {
        File file = Files.createTempFile("test-avro-agg", ".avro").toFile();
        file.deleteOnExit();

        DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(SCHEMA);
        try (DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<>(datumWriter)) {
            if (codecFactory != null) {
                dataFileWriter.setCodec(codecFactory);
            }
            dataFileWriter.create(SCHEMA, file);

            for (String value : data) {
                GenericRecord record = new GenericData.Record(SCHEMA);
                record.put("value", value);
                dataFileWriter.append(record);
            }
        }

        return file;
    }
}