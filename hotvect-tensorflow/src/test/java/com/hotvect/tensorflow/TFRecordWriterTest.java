package com.hotvect.tensorflow;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TFRecordWriterTest {

    @Test
    void testCreateTfRecordByteBufferEmpty() {
        TFRecordWriter writer = new TFRecordWriter();

        List<ByteBuffer> emptyList = Collections.emptyList();
        ByteBuffer result = writer.createTfRecordByteBuffer(emptyList);

        assertNotNull(result);
        assertEquals(0, result.remaining());
    }

    @Test
    void testCreateTfRecordByteBufferSingle() throws IOException {
        TFRecordWriter writer = new TFRecordWriter();

        // Create single record
        String testData = "hello world";
        ByteBuffer input = ByteBuffer.wrap(testData.getBytes(StandardCharsets.UTF_8));
        List<ByteBuffer> records = Arrays.asList(input);

        ByteBuffer result = writer.createTfRecordByteBuffer(records);

        // Verify we can read the data back using TFRecordCodec
        TFRecordCodec codec = new TFRecordCodec();
        ByteArrayInputStream bais = new ByteArrayInputStream(result.array(), result.position(), result.remaining());
        ReadableByteChannel channel = Channels.newChannel(bais);

        byte[] readData = codec.read(channel);
        assertNotNull(readData);
        assertEquals(testData, new String(readData, StandardCharsets.UTF_8));

        // Should be no more records
        assertNull(codec.read(channel));
    }

    @Test
    void testCreateTfRecordByteBufferSingleBytesMatchesListPath() {
        TFRecordWriter writer = new TFRecordWriter();

        byte[] record = "hello world".getBytes(StandardCharsets.UTF_8);

        ByteBuffer fromBytes = writer.createTfRecordByteBuffer(record);
        ByteBuffer fromList = writer.createTfRecordByteBuffer(List.of(ByteBuffer.wrap(record)));

        assertArrayEquals(fromList.array(), fromBytes.array());
        assertEquals(fromList.position(), fromBytes.position());
        assertEquals(fromList.remaining(), fromBytes.remaining());
    }

    @Test
    void testCreateTfRecordByteBufferMultiple() throws IOException {
        TFRecordWriter writer = new TFRecordWriter();

        // Create multiple records
        String[] testStrings = {"foo", "bar", "baz", "hello world"};
        List<ByteBuffer> records = new ArrayList<>();

        for (String testString : testStrings) {
            records.add(ByteBuffer.wrap(testString.getBytes(StandardCharsets.UTF_8)));
        }

        ByteBuffer result = writer.createTfRecordByteBuffer(records);

        // Verify we can read all data back using TFRecordCodec
        TFRecordCodec codec = new TFRecordCodec();
        ByteArrayInputStream bais = new ByteArrayInputStream(result.array(), result.position(), result.remaining());
        ReadableByteChannel channel = Channels.newChannel(bais);

        for (String expected : testStrings) {
            byte[] readData = codec.read(channel);
            assertNotNull(readData, "Should read record for: " + expected);
            assertEquals(expected, new String(readData, StandardCharsets.UTF_8));
        }

        // Should be no more records
        assertNull(codec.read(channel));
    }

    @Test
    void testCreateTfRecordByteBufferWithEmptyRecord() throws IOException {
        TFRecordWriter writer = new TFRecordWriter();

        // Mix of empty and non-empty records
        List<ByteBuffer> records = Arrays.asList(
            ByteBuffer.wrap("first".getBytes(StandardCharsets.UTF_8)),
            ByteBuffer.wrap(new byte[0]), // empty record
            ByteBuffer.wrap("third".getBytes(StandardCharsets.UTF_8))
        );

        ByteBuffer result = writer.createTfRecordByteBuffer(records);

        // Verify we can read all data back
        TFRecordCodec codec = new TFRecordCodec();
        ByteArrayInputStream bais = new ByteArrayInputStream(result.array(), result.position(), result.remaining());
        ReadableByteChannel channel = Channels.newChannel(bais);

        // Read first record
        byte[] first = codec.read(channel);
        assertEquals("first", new String(first, StandardCharsets.UTF_8));

        // Read empty record
        byte[] empty = codec.read(channel);
        assertNotNull(empty);
        assertEquals(0, empty.length);

        // Read third record
        byte[] third = codec.read(channel);
        assertEquals("third", new String(third, StandardCharsets.UTF_8));

        // Should be no more records
        assertNull(codec.read(channel));
    }

    @Test
    void testToByteArray() {
        TFRecordWriter writer = new TFRecordWriter();

        String testData = "test data";
        ByteBuffer buffer = ByteBuffer.wrap(testData.getBytes(StandardCharsets.UTF_8));

        // Test that toByteArray doesn't modify the original buffer
        int originalPosition = buffer.position();
        int originalLimit = buffer.limit();

        byte[] result = writer.toByteArray(buffer);

        // Verify buffer is unchanged
        assertEquals(originalPosition, buffer.position());
        assertEquals(originalLimit, buffer.limit());

        // Verify extracted data is correct
        assertEquals(testData, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void testToByteArrayWithPartialBuffer() {
        TFRecordWriter writer = new TFRecordWriter();

        String fullData = "hello world";
        ByteBuffer buffer = ByteBuffer.wrap(fullData.getBytes(StandardCharsets.UTF_8));

        // Advance position to skip "hello "
        buffer.position(6);

        byte[] result = writer.toByteArray(buffer);

        // Should only get "world"
        assertEquals("world", new String(result, StandardCharsets.UTF_8));

        // Buffer position should be unchanged
        assertEquals(6, buffer.position());
    }

    @Test
    void testCreateTfRecordByteBufferWithLargeData() throws IOException {
        TFRecordWriter writer = new TFRecordWriter();

        // Create large record (1MB)
        byte[] largeData = new byte[1024 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        List<ByteBuffer> records = Arrays.asList(ByteBuffer.wrap(largeData));
        ByteBuffer result = writer.createTfRecordByteBuffer(records);

        // Verify we can read the large data back
        TFRecordCodec codec = new TFRecordCodec();
        ByteArrayInputStream bais = new ByteArrayInputStream(result.array(), result.position(), result.remaining());
        ReadableByteChannel channel = Channels.newChannel(bais);

        byte[] readData = codec.read(channel);
        assertArrayEquals(largeData, readData);
    }

    @Test
    void testCreateTfRecordByteBufferUsesCodecCorrectly() {
        // Test with mock codec to verify interaction
        TFRecordCodec mockCodec = Mockito.mock(TFRecordCodec.class);
        TFRecordWriter writer = new TFRecordWriter(mockCodec);

        byte[] testData1 = "test1".getBytes(StandardCharsets.UTF_8);
        byte[] testData2 = "test2".getBytes(StandardCharsets.UTF_8);

        when(mockCodec.recordLength(testData1)).thenReturn(20);
        when(mockCodec.recordLength(testData2)).thenReturn(25);

        List<ByteBuffer> records = Arrays.asList(
            ByteBuffer.wrap(testData1),
            ByteBuffer.wrap(testData2)
        );

        ByteBuffer result = writer.createTfRecordByteBuffer(records);

        // Verify codec methods were called correctly
        verify(mockCodec).recordLength(testData1);
        verify(mockCodec).recordLength(testData2);
        verify(mockCodec).writeTfRecord(any(ByteBuffer.class), eq(testData1));
        verify(mockCodec).writeTfRecord(any(ByteBuffer.class), eq(testData2));

        // Verify result buffer size is correct
        assertEquals(45, result.capacity()); // 20 + 25
    }

    @Test
    void testCreateTfRecordByteBufferPreservesRecordOrder() throws IOException {
        TFRecordWriter writer = new TFRecordWriter();

        // Create records in specific order
        String[] orderedData = {"first", "second", "third", "fourth", "fifth"};
        List<ByteBuffer> records = new ArrayList<>();

        for (String data : orderedData) {
            records.add(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
        }

        ByteBuffer result = writer.createTfRecordByteBuffer(records);

        // Verify records are read back in the same order
        TFRecordCodec codec = new TFRecordCodec();
        ByteArrayInputStream bais = new ByteArrayInputStream(result.array(), result.position(), result.remaining());
        ReadableByteChannel channel = Channels.newChannel(bais);

        for (String expected : orderedData) {
            byte[] readData = codec.read(channel);
            assertNotNull(readData, "Should read record for: " + expected);
            assertEquals(expected, new String(readData, StandardCharsets.UTF_8));
        }

        assertNull(codec.read(channel), "Should be no more records");
    }

    @Test
    void testCreateTfRecordByteBufferResultIsRewound() {
        TFRecordWriter writer = new TFRecordWriter();

        String testData = "test";
        List<ByteBuffer> records = Arrays.asList(
            ByteBuffer.wrap(testData.getBytes(StandardCharsets.UTF_8))
        );

        ByteBuffer result = writer.createTfRecordByteBuffer(records);

        // Result should be rewound (position = 0)
        assertEquals(0, result.position());
        assertTrue(result.remaining() > 0);
    }
}
