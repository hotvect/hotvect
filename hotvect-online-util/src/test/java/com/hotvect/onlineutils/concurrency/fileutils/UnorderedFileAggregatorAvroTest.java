package com.hotvect.onlineutils.concurrency.fileutils;

import com.google.common.collect.ImmutableList;
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
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class UnorderedFileAggregatorAvroTest {
    private static final SimpleMeterRegistry mr = new SimpleMeterRegistry();

    private static final Schema SCHEMA = new Schema.Parser().parse(
            "{\"type\":\"record\",\"name\":\"TestRecord\",\"fields\":[" +
            "{\"name\":\"value\",\"type\":\"string\"}," +
            "{\"name\":\"count\",\"type\":\"int\"}" +
            "]}"
    );

    @Test
    void testAvroWithoutCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(
                                new TestData("apple", 5),
                                new TestData("banana", 10),
                                new TestData("cherry", 15)
                        ),
                        ImmutableList.of(
                                new TestData("date", 20),
                                new TestData("elderberry", 25),
                                new TestData("fig", 30)
                        )
                ),
                null // No compression
        );

        try {
            // Test with AtomicLong for sum aggregation
            AtomicLong totalCount = new AtomicLong(0);
            BiConsumer<AtomicLong, GenericRecord> sumUpdate = (state, record) -> {
                int count = (Integer) record.get("count");
                state.addAndGet(count);
            };

            UnorderedFileAggregator<GenericRecord, AtomicLong> testSubject =
                UnorderedFileAggregator.aggregator(mr, source, totalCount, sumUpdate);

            Map<String, Object> metadata = testSubject.call();

            // Verify the sum: 5+10+15+20+25+30 = 105
            assertEquals(105L, totalCount.get());

            // Verify metadata
            assertNotNull(metadata);
            assertEquals(6L, metadata.get("records_processed"));
            assertEquals(2, metadata.get("number_of_files_read"));

        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithSnappyCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(
                                new TestData("value1", 1),
                                new TestData("value2", 2),
                                new TestData("value3", 3)
                        ),
                        ImmutableList.of(
                                new TestData("value4", 4),
                                new TestData("value5", 5),
                                new TestData("value6", 6)
                        )
                ),
                CodecFactory.snappyCodec()
        );

        try {
            // Test with ConcurrentHashMap for collecting values
            Set<String> collectedValues = ConcurrentHashMap.newKeySet();
            BiConsumer<Set<String>, GenericRecord> collectUpdate = (state, record) -> {
                String value = record.get("value").toString(); // Handle Avro Utf8 objects
                state.add(value);
            };

            UnorderedFileAggregator<GenericRecord, Set<String>> testSubject =
                UnorderedFileAggregator.aggregator(mr, source, collectedValues, collectUpdate);

            Map<String, Object> metadata = testSubject.call();

            assertNotNull(collectedValues);
            assertEquals(6, collectedValues.size());

            Set<String> expected = Set.of("value1", "value2", "value3", "value4", "value5", "value6");
            assertEquals(expected, collectedValues);

            // Verify metadata
            assertEquals(6L, metadata.get("records_processed"));
            assertEquals(2, metadata.get("number_of_files_read"));

        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithDeflateCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(
                                new TestData("alpha", 100),
                                new TestData("beta", 200),
                                new TestData("gamma", 300)
                        ),
                        ImmutableList.of(
                                new TestData("delta", 400),
                                new TestData("epsilon", 500),
                                new TestData("zeta", 600)
                        )
                ),
                CodecFactory.deflateCodec(6)
        );

        try {
            // Test with Map for counting occurrences
            Map<String, AtomicLong> valueCounters = new ConcurrentHashMap<>();
            BiConsumer<Map<String, AtomicLong>, GenericRecord> countUpdate = (state, record) -> {
                String value = record.get("value").toString();
                int count = (Integer) record.get("count");
                state.computeIfAbsent(value, k -> new AtomicLong(0)).addAndGet(count);
            };

            UnorderedFileAggregator<GenericRecord, Map<String, AtomicLong>> testSubject =
                UnorderedFileAggregator.aggregator(mr, source, valueCounters, countUpdate);

            Map<String, Object> metadata = testSubject.call();

            assertNotNull(valueCounters);
            assertEquals(6, valueCounters.size());

            assertEquals(100L, valueCounters.get("alpha").get());
            assertEquals(200L, valueCounters.get("beta").get());
            assertEquals(300L, valueCounters.get("gamma").get());
            assertEquals(400L, valueCounters.get("delta").get());
            assertEquals(500L, valueCounters.get("epsilon").get());
            assertEquals(600L, valueCounters.get("zeta").get());

        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithFilterAggregation() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(
                                new TestData("short", 1),
                                new TestData("verylongstring", 100),
                                new TestData("mid", 2)
                        ),
                        ImmutableList.of(
                                new TestData("x", 3),
                                new TestData("anotherlongstring", 200),
                                new TestData("ok", 4)
                        )
                ),
                null
        );

        try {
            // Only collect strings longer than 5 characters
            Set<String> longStrings = ConcurrentHashMap.newKeySet();
            AtomicLong totalLongStringCount = new AtomicLong(0);

            BiConsumer<Map<String, Object>, GenericRecord> filterUpdate = (state, record) -> {
                String value = record.get("value").toString(); // Handle Avro Utf8 objects
                int count = (Integer) record.get("count");
                if (value.length() > 5) {
                    @SuppressWarnings("unchecked")
                    Set<String> strings = (Set<String>) state.get("strings");
                    AtomicLong total = (AtomicLong) state.get("total");
                    strings.add(value);
                    total.addAndGet(count);
                }
            };

            Map<String, Object> state = new ConcurrentHashMap<>();
            state.put("strings", longStrings);
            state.put("total", totalLongStringCount);

            UnorderedFileAggregator<GenericRecord, Map<String, Object>> testSubject =
                UnorderedFileAggregator.aggregator(mr, source, state, filterUpdate);

            Map<String, Object> metadata = testSubject.call();

            assertNotNull(longStrings);
            assertEquals(2, longStrings.size());
            assertTrue(longStrings.contains("verylongstring"));
            assertTrue(longStrings.contains("anotherlongstring"));
            assertFalse(longStrings.contains("short"));
            assertFalse(longStrings.contains("x"));

            // Total count should be 100 + 200 = 300
            assertEquals(300L, totalLongStringCount.get());

        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void failFastOnError() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(
                                new TestData("test1", 1),
                                new TestData("test2", 2),
                                new TestData("test3", 3)
                        )
                ),
                null
        );

        try {
            Set<String> result = ConcurrentHashMap.newKeySet();
            BiConsumer<Set<String>, GenericRecord> badUpdate = (state, record) -> {
                String value = record.get("value").toString(); // Handle Avro Utf8 objects
                if (value.equals("test2")) {
                    throw new RuntimeException("Intentional test error");
                }
                state.add(value);
            };

            UnorderedFileAggregator<GenericRecord, Set<String>> testSubject =
                UnorderedFileAggregator.aggregator(mr, source, result, badUpdate);

            assertThrows(RuntimeException.class, testSubject::call);
        } finally {
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithCustomThreadsAndBatchSize() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(
                                new TestData("item1", 1),
                                new TestData("item2", 2),
                                new TestData("item3", 3),
                                new TestData("item4", 4),
                                new TestData("item5", 5)
                        ),
                        ImmutableList.of(
                                new TestData("item6", 6),
                                new TestData("item7", 7),
                                new TestData("item8", 8),
                                new TestData("item9", 9),
                                new TestData("item10", 10)
                        )
                ),
                CodecFactory.snappyCodec()
        );

        try {
            Set<String> result = ConcurrentHashMap.newKeySet();
            AtomicLong totalCount = new AtomicLong(0);
            BiConsumer<Map<String, Object>, GenericRecord> aggregateUpdate = (state, record) -> {
                String value = record.get("value").toString();
                int count = (Integer) record.get("count");

                @SuppressWarnings("unchecked")
                Set<String> strings = (Set<String>) state.get("strings");
                AtomicLong total = (AtomicLong) state.get("total");

                strings.add(value);
                total.addAndGet(count);
            };

            Map<String, Object> state = new ConcurrentHashMap<>();
            state.put("strings", result);
            state.put("total", totalCount);

            UnorderedFileAggregator<GenericRecord, Map<String, Object>> testSubject =
                UnorderedFileAggregator.aggregator(
                    mr,
                    source,
                    state,
                    aggregateUpdate,
                    4, // numThreads
                    3  // batchSize
                );

            Map<String, Object> metadata = testSubject.call();

            assertNotNull(result);
            assertEquals(10, result.size());

            for (int i = 1; i <= 10; i++) {
                assertTrue(result.contains("item" + i));
            }

            // Total should be 1+2+3+4+5+6+7+8+9+10 = 55
            assertEquals(55L, totalCount.get());

            // Verify metadata
            assertEquals(10L, metadata.get("records_processed"));
            assertEquals(2, metadata.get("number_of_files_read"));

        } finally {
            source.forEach(File::delete);
        }
    }

    /**
     * Helper class to represent test data structure
     */
    private static class TestData {
        final String value;
        final int count;

        TestData(String value, int count) {
            this.value = value;
            this.count = count;
        }
    }

    /**
     * Creates test Avro files with optional compression.
     *
     * @param fileData list of TestData lists, each representing one file's data
     * @param codecFactory compression codec (null for no compression)
     * @return list of created temporary files
     */
    private List<File> createTestAvroFiles(List<List<TestData>> fileData, CodecFactory codecFactory) throws IOException {
        List<File> files = new ArrayList<>();
        for (List<TestData> data : fileData) {
            File file = createSingleAvroFile(data, codecFactory);
            files.add(file);
        }
        return files;
    }

    /**
     * Creates a single Avro file with the given data and compression.
     */
    private File createSingleAvroFile(List<TestData> data, CodecFactory codecFactory) throws IOException {
        File file = Files.createTempFile("test-avro-ufa", ".avro").toFile();
        file.deleteOnExit();

        DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(SCHEMA);
        try (DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<>(datumWriter)) {
            if (codecFactory != null) {
                dataFileWriter.setCodec(codecFactory);
            }
            dataFileWriter.create(SCHEMA, file);

            for (TestData testData : data) {
                GenericRecord record = new GenericData.Record(SCHEMA);
                record.put("value", testData.value);
                record.put("count", testData.count);
                dataFileWriter.append(record);
            }
        }

        return file;
    }
}