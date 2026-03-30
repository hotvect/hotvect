package com.hotvect.tensorflow;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TFRecordCodecTest {

    // Base64-encoded TFRecord containing "foo" (from Apache Beam reference)
    private static final String FOO_RECORD_BASE64 = "AwAAAAAAAACwmUkOZm9vYYq+/g==";

    // Base64-encoded TFRecord containing both "foo" and "bar" (from Apache Beam reference)
    private static final String FOO_BAR_RECORD_BASE64 = "AwAAAAAAAACwmUkOZm9vYYq+/gMAAAAAAAAAsJlJDmJhckYA5cg=";

    @Test
    void testMask() {
        TFRecordCodec codec = new TFRecordCodec();

        // Test the TFRecord masking formula
        int testCrc = 0x12345678;
        int masked = codec.mask(testCrc);

        // Verify the masking formula: ((crc >>> 15) | (crc << 17)) + 0xa282ead8
        int expected = ((testCrc >>> 15) | (testCrc << 17)) + 0xa282ead8;
        assertEquals(expected, masked);
    }

    @Test
    void testHashLong() {
        TFRecordCodec codec = new TFRecordCodec();

        // Test that hashLong produces consistent results
        long testValue = 123456789L;
        int hash1 = codec.hashLong(testValue);
        int hash2 = codec.hashLong(testValue);

        assertEquals(hash1, hash2, "Hash should be consistent");

        // Different values should produce different hashes (with high probability)
        int hash3 = codec.hashLong(testValue + 1);
        assertNotEquals(hash1, hash3, "Different values should produce different hashes");
    }

    @Test
    void testHashBytes() {
        TFRecordCodec codec = new TFRecordCodec();

        byte[] testData = "test data".getBytes(StandardCharsets.UTF_8);
        int hash1 = codec.hashBytes(testData);
        int hash2 = codec.hashBytes(testData);

        assertEquals(hash1, hash2, "Hash should be consistent");

        // Different data should produce different hashes
        byte[] differentData = "different data".getBytes(StandardCharsets.UTF_8);
        int hash3 = codec.hashBytes(differentData);
        assertNotEquals(hash1, hash3, "Different data should produce different hashes");
    }

    @Test
    void testRecordLength() {
        TFRecordCodec codec = new TFRecordCodec();

        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        int length = codec.recordLength(data);

        // TFRecord format: 8 bytes (length) + 4 bytes (length CRC) + data + 4 bytes (data CRC)
        int expected = 8 + 4 + data.length + 4;
        assertEquals(expected, length);
    }

    @Test
    void testWriteTfRecordToByteBuffer() {
        TFRecordCodec codec = new TFRecordCodec();

        byte[] data = "foo".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(codec.recordLength(data));

        // Test writing to ByteBuffer
        assertDoesNotThrow(() -> codec.writeTfRecord(buffer, data));

        // Verify buffer position advanced correctly
        assertEquals(codec.recordLength(data), buffer.position());

        // Verify we can read the data back
        buffer.rewind();
        assertEquals(data.length, buffer.order(ByteOrder.LITTLE_ENDIAN).getLong());
    }

    @Test
    void testWriteTfRecordInsufficientCapacity() {
        TFRecordCodec codec = new TFRecordCodec();

        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer smallBuffer = ByteBuffer.allocate(5); // Too small

        // Should throw exception for insufficient capacity
        assertThrows(IllegalArgumentException.class, () -> codec.writeTfRecord(smallBuffer, data));
    }

    @Test
    void testTFRecordCodecRoundTrip() throws IOException {
        TFRecordCodec codec = new TFRecordCodec();

        // Test data
        String[] testStrings = {"foo", "bar", "hello world", ""};

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WritableByteChannel outChannel = Channels.newChannel(baos);

        // Write all test data
        for (String testString : testStrings) {
            byte[] data = testString.getBytes(StandardCharsets.UTF_8);
            codec.write(outChannel, data);
        }

        // Read all test data back
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ReadableByteChannel inChannel = Channels.newChannel(bais);

        for (String expected : testStrings) {
            byte[] read = codec.read(inChannel);
            assertNotNull(read, "Should read data");
            assertEquals(expected, new String(read, StandardCharsets.UTF_8));
        }

        // Should return null when no more data
        assertNull(codec.read(inChannel));
    }

    @Test
    void testTFRecordCodecWithKnownData() throws IOException {
        TFRecordCodec codec = new TFRecordCodec();
        Base64.Decoder decoder = Base64.getDecoder();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WritableByteChannel outChannel = Channels.newChannel(baos);

        // Write "foo" record
        codec.write(outChannel, "foo".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(decoder.decode(FOO_RECORD_BASE64), baos.toByteArray());

        // Write "bar" record
        codec.write(outChannel, "bar".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(decoder.decode(FOO_BAR_RECORD_BASE64), baos.toByteArray());

        // Read back the data to verify round-trip functionality
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ReadableByteChannel inChannel = Channels.newChannel(bais);

        byte[] foo = codec.read(inChannel);
        byte[] bar = codec.read(inChannel);
        assertNull(codec.read(inChannel));

        assertEquals("foo", new String(foo, StandardCharsets.UTF_8));
        assertEquals("bar", new String(bar, StandardCharsets.UTF_8));
    }

    @Test
    void testReadInvalidRecord() throws IOException {
        TFRecordCodec codec = new TFRecordCodec();

        // Create invalid record (too short)
        byte[] invalidData = new byte[5]; // Less than 12 bytes required for header
        ByteArrayInputStream bais = new ByteArrayInputStream(invalidData);
        ReadableByteChannel inChannel = Channels.newChannel(bais);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> codec.read(inChannel));
        assertTrue(exception.getMessage().contains("Not a valid TFRecord"));
    }

    @Test
    void testReadInvalidLengthMask() throws IOException {
        TFRecordCodec codec = new TFRecordCodec();

        // Create record with invalid length CRC
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(4); // length = 4
        buffer.putInt(0x12345678); // wrong CRC
        buffer.put("test".getBytes(StandardCharsets.UTF_8)); // 4 bytes data
        buffer.putInt(0x87654321); // data CRC (doesn't matter for this test)

        ByteArrayInputStream bais = new ByteArrayInputStream(buffer.array());
        ReadableByteChannel inChannel = Channels.newChannel(bais);

        IOException exception = assertThrows(IOException.class, () -> codec.read(inChannel));
        assertTrue(exception.getMessage().contains("Mismatch of length mask"));
    }

    @Test
    void testReadInvalidDataMask() throws IOException {
        TFRecordCodec codec = new TFRecordCodec();

        // Create a valid record first to get the correct length CRC
        byte[] testData = "test".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WritableByteChannel outChannel = Channels.newChannel(baos);
        codec.write(outChannel, testData);

        // Corrupt the data CRC (last 4 bytes)
        byte[] recordData = baos.toByteArray();
        recordData[recordData.length - 1] = (byte) 0xFF; // Corrupt last byte

        ByteArrayInputStream bais = new ByteArrayInputStream(recordData);
        ReadableByteChannel inChannel = Channels.newChannel(bais);

        IOException exception = assertThrows(IOException.class, () -> codec.read(inChannel));
        assertTrue(exception.getMessage().contains("Mismatch of data mask"));
    }

    @Test
    void testEmptyRecord() throws IOException {
        TFRecordCodec codec = new TFRecordCodec();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WritableByteChannel outChannel = Channels.newChannel(baos);

        // Write empty record
        codec.write(outChannel, new byte[0]);

        // Read it back
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ReadableByteChannel inChannel = Channels.newChannel(bais);

        byte[] result = codec.read(inChannel);
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    void testLargeRecord() throws IOException {
        TFRecordCodec codec = new TFRecordCodec();

        // Create large test data (64KB)
        byte[] largeData = new byte[65536];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WritableByteChannel outChannel = Channels.newChannel(baos);

        // Write large record
        codec.write(outChannel, largeData);

        // Read it back
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ReadableByteChannel inChannel = Channels.newChannel(bais);

        byte[] result = codec.read(inChannel);
        assertArrayEquals(largeData, result);
    }

    @Test
    void testByteOrderPreservation() {
        TFRecordCodec codec = new TFRecordCodec();

        ByteBuffer buffer = ByteBuffer.allocate(100);
        ByteOrder originalOrder = ByteOrder.BIG_ENDIAN;
        buffer.order(originalOrder);

        byte[] testData = "test".getBytes(StandardCharsets.UTF_8);
        codec.writeTfRecord(buffer, testData);

        // Verify original byte order is restored
        assertEquals(originalOrder, buffer.order());
    }
}