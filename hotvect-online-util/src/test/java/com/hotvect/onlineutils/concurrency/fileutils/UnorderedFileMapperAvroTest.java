package com.hotvect.onlineutils.concurrency.fileutils;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class UnorderedFileMapperAvroTest {
    private static final SimpleMeterRegistry mr = new SimpleMeterRegistry();

    private static final Schema SCHEMA = new Schema.Parser().parse(
            "{\"type\":\"record\",\"name\":\"TestRecord\",\"fields\":[" +
            "{\"name\":\"value\",\"type\":\"int\"}" +
            "]}"
    );

    // Transformation logic for testing
    @SuppressWarnings("unchecked")
    private final Function<GenericRecord, List<ByteBuffer>> transformFunTyped = record -> {
        int parsed = (Integer) record.get("value");
        if (parsed % 4 == 0) {
            return Collections.emptyList();
        } else if (parsed % 3 == 0) {
            return List.of(ByteBuffer.wrap((parsed + "\n").getBytes(StandardCharsets.UTF_8)));
        } else {
            int hashed = Hashing.murmur3_32_fixed().hashInt(parsed).asInt();
            return List.of(
                ByteBuffer.wrap((parsed + "\n").getBytes(StandardCharsets.UTF_8)),
                ByteBuffer.wrap((hashed + "\n").getBytes(StandardCharsets.UTF_8))
            );
        }
    };

    @SuppressWarnings({"rawtypes", "unchecked"})
    private final Function transformFun = (Function) transformFunTyped;

    @Test
    void testAvroWithoutCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(1, 2, 3),
                        ImmutableList.of(4, 5, 6)
                ),
                null // No compression
        );

        File dest = Files.createTempFile("avro-test-output", ".txt").toFile();
        dest.deleteOnExit();
        File partFile = new File(dest.getParentFile(), "part-00000.txt");
        partFile.deleteOnExit();

        try {
            UnorderedFileMapper<String> testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .build();

            Map<String, Object> result = testSubject.call();

            assertEquals(6L, result.get("lines_read"));
            assertEquals(2, result.get("number_of_files_read"));

            List<String> outputLines = FileUtils.readLines(partFile).toList();
            assertEquals(8, outputLines.size());

            Set<String> expectedValues = new HashSet<>(List.of("1", "2", "3", "5", "6"));
            assertTrue(outputLines.containsAll(expectedValues));
        } finally {
            dest.delete();
            partFile.delete();
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithSnappyCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(1, 2, 3, 7, 11),
                        ImmutableList.of(4, 5, 6, 8, 12)
                ),
                CodecFactory.snappyCodec()
        );

        File dest = Files.createTempFile("avro-snappy-test-output", ".txt").toFile();
        dest.deleteOnExit();
        File partFile = new File(dest.getParentFile(), "part-00000.txt");
        partFile.deleteOnExit();

        try {
            UnorderedFileMapper<String> testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .build();

            Map<String, Object> result = testSubject.call();

            assertEquals(10L, result.get("lines_read"));
            assertEquals(2, result.get("number_of_files_read"));

            List<String> outputLines = FileUtils.readLines(partFile).toList();
            assertTrue(outputLines.size() > 10); // Should have records + hashed values

            // Verify content: values not divisible by 4 should be present
            Set<String> expectedValues = new HashSet<>(List.of("1", "2", "3", "5", "6", "7", "11"));
            for (String expected : expectedValues) {
                assertTrue(outputLines.contains(expected),
                    "Expected to find value " + expected + " in output");
            }
        } finally {
            dest.delete();
            partFile.delete();
            source.forEach(File::delete);
        }
    }

    @Test
    void testAvroWithDeflateCompression() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(10, 20, 30),
                        ImmutableList.of(40, 50, 60)
                ),
                CodecFactory.deflateCodec(6)
        );

        File dest = Files.createTempFile("avro-deflate-test-output", ".txt").toFile();
        dest.deleteOnExit();
        File partFile = new File(dest.getParentFile(), "part-00000.txt");
        partFile.deleteOnExit();

        try {
            UnorderedFileMapper<String> testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .build();

            Map<String, Object> result = testSubject.call();

            assertEquals(6L, result.get("lines_read"));
            assertEquals(2, result.get("number_of_files_read"));

            List<String> outputLines = FileUtils.readLines(partFile).toList();
            // Values: 10, 20, 30, 40, 50, 60
            // 20, 40, 60 divisible by 4 -> filtered out
            // 30 divisible by 3 -> only value
            // 10, 50 -> value + hash
            assertEquals(5, outputLines.size());

            Set<String> expectedValues = new HashSet<>(List.of("10", "30", "50"));
            for (String expected : expectedValues) {
                assertTrue(outputLines.contains(expected),
                    "Expected to find value " + expected + " in output");
            }
        } finally {
            dest.delete();
            partFile.delete();
            source.forEach(File::delete);
        }
    }

    @Test
    void failFastOnError() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(1, 2, 3),
                        ImmutableList.of(4, 5, 6)
                ),
                null
        );

        File dest = Files.createTempFile("avro-fail-test", ".txt").toFile();
        dest.deleteOnExit();

        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Function badFun = (Function) (Function<GenericRecord, List<ByteBuffer>>) record -> {
                int i = (Integer) record.get("value");
                if (i % 2 == 0) {
                    throw new RuntimeException("Intentional test error");
                } else if (i % 3 == 0) {
                    try {
                        Thread.sleep(TimeUnit.DAYS.toMillis(1));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                throw new RuntimeException("Bad function");
            };

            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(source, dest, badFun)
                    .meterRegistry(mr)
                    .build();

            assertThrows(Throwable.class, testSubject::call);
        } finally {
            dest.delete();
            source.forEach(File::delete);
        }
    }

    @Test
    void rejectMixedTextAndAvroFiles() throws Exception {
        File textFile = Files.createTempFile("test", ".json").toFile();
        Files.writeString(textFile.toPath(), "{\"value\": 1}\n");

        File avroFile = createSingleAvroFile(List.of(1, 2, 3), null);

        File dest = Files.createTempFile("mixed-test-output", ".txt").toFile();

        try {
            UnorderedFileMapper testSubject = UnorderedFileMapper.builder(
                    List.of(textFile, avroFile), dest, transformFun)
                    .meterRegistry(mr)
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, testSubject::call);
            assertTrue(exception.getMessage().contains("Mixed file formats") ||
                       exception.getCause().getMessage().contains("Mixed file formats"));
        } finally {
            textFile.delete();
            avroFile.delete();
            dest.delete();
        }
    }

    @Test
    void testAvroWithMultipleThreadsAndBatchSizes() throws Exception {
        List<File> source = createTestAvroFiles(
                ImmutableList.of(
                        ImmutableList.of(1, 2, 3, 4, 5),
                        ImmutableList.of(6, 7, 8, 9, 10),
                        ImmutableList.of(11, 12, 13, 14, 15)
                ),
                CodecFactory.snappyCodec()
        );

        File dest = Files.createTempFile("avro-multithread-test", ".txt").toFile();
        dest.deleteOnExit();

        try {
            UnorderedFileMapper<GenericRecord> testSubject = UnorderedFileMapper.builder(source, dest, transformFun)
                    .meterRegistry(mr)
                    .nThreads(4)
                    .batchSize(2)
                    .build();

            Map<String, Object> result = testSubject.call();

            assertEquals(15L, result.get("lines_read"));
            assertEquals(3, result.get("number_of_files_read"));

            long outputLines = (Long) result.get("lines_written");
            // 4, 8, 12 are filtered (divisible by 4)
            // 3, 6, 9, 15 are divisible by 3 (single output)
            // Others produce 2 outputs (value + hash)
            assertTrue(outputLines > 0);
        } finally {
            dest.delete();
            source.forEach(File::delete);
        }
    }

    /**
     * Creates test Avro files with optional compression.
     *
     * @param fileData list of integer lists, each representing one file's data
     * @param codecFactory compression codec (null for no compression)
     * @return list of created temporary files
     */
    private List<File> createTestAvroFiles(List<List<Integer>> fileData, CodecFactory codecFactory) throws IOException {
        List<File> files = new ArrayList<>();
        for (List<Integer> data : fileData) {
            File file = createSingleAvroFile(data, codecFactory);
            files.add(file);
        }
        return files;
    }

    /**
     * Creates a single Avro file with the given data and compression.
     */
    private File createSingleAvroFile(List<Integer> data, CodecFactory codecFactory) throws IOException {
        File file = Files.createTempFile("test-avro", ".avro").toFile();
        file.deleteOnExit();

        DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(SCHEMA);
        try (DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<>(datumWriter)) {
            if (codecFactory != null) {
                dataFileWriter.setCodec(codecFactory);
            }
            dataFileWriter.create(SCHEMA, file);

            for (Integer value : data) {
                GenericRecord record = new GenericData.Record(SCHEMA);
                record.put("value", value);
                dataFileWriter.append(record);
            }
        }

        return file;
    }
}
