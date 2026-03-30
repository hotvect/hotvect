package com.hotvect.tensorflow;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static com.google.common.base.Preconditions.checkState;

/**
 * TFRecord codec that exactly matches Apache Beam's implementation but is thread-safe.
 *
 * This implementation creates new ByteBuffer objects instead of reusing them,
 * making it safe for concurrent use unlike the original Apache Beam version.
 *
 * Based on Apache Beam's TFRecordCodec from:
 * org.apache.beam.sdk.io.TFRecordIO.TFRecordCodec
 */
public class TFRecordCodec {
    private static final int HEADER_LEN = (Long.SIZE + Integer.SIZE) / Byte.SIZE; // 12 bytes
    private static final int FOOTER_LEN = Integer.SIZE / Byte.SIZE; // 4 bytes
    private static final HashFunction crc32c = Hashing.crc32c();

    /**
     * Applies the TFRecord CRC32C masking formula.
     * This is the exact masking used by TensorFlow's TFRecord format.
     */
    @VisibleForTesting
    int mask(int crc) {
        return ((crc >>> 15) | (crc << 17)) + 0xa282ead8;
    }

    /**
     * Hashes a long value using CRC32C and applies TFRecord masking.
     */
    @VisibleForTesting
    int hashLong(long x) {
        return mask(crc32c.hashLong(x).asInt());
    }

    /**
     * Hashes a byte array using CRC32C and applies TFRecord masking.
     */
    @VisibleForTesting
    int hashBytes(byte[] x) {
        return mask(crc32c.hashBytes(x).asInt());
    }

    /**
     * Calculates the total length of a TFRecord for the given data.
     */
    public int recordLength(byte[] data) {
        return HEADER_LEN + data.length + FOOTER_LEN;
    }

    /**
     * Reads a single TFRecord from the channel.
     * Returns null if no more records are available.
     */
    public byte[] read(ReadableByteChannel inChannel) throws IOException {
        // Thread-safe: create new ByteBuffer instead of reusing
        ByteBuffer header = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN);
        int headerBytes = read(inChannel, header);
        if (headerBytes == 0) {
            return null;
        }
        checkState(headerBytes == HEADER_LEN, "Not a valid TFRecord. Fewer than 12 bytes.");

        header.rewind();
        long length64 = header.getLong();
        long lengthHash = hashLong(length64);
        int maskedCrc32OfLength = header.getInt();
        if (lengthHash != maskedCrc32OfLength) {
            throw new IOException(
                String.format(
                    "Mismatch of length mask when reading a record. Expected %d but received %d.",
                    maskedCrc32OfLength, lengthHash));
        }
        int length = (int) length64;
        if (length != length64) {
            throw new IOException(String.format("length overflow %d", length64));
        }

        ByteBuffer data = ByteBuffer.allocate(length);
        readFully(inChannel, data);

        // Thread-safe: create new ByteBuffer instead of reusing
        ByteBuffer footer = ByteBuffer.allocate(FOOTER_LEN).order(ByteOrder.LITTLE_ENDIAN);
        readFully(inChannel, footer);
        footer.rewind();

        int maskedCrc32OfData = footer.getInt();
        int dataHash = hashBytes(data.array());
        if (dataHash != maskedCrc32OfData) {
            throw new IOException(
                String.format(
                    "Mismatch of data mask when reading a record. Expected %d but received %d.",
                    maskedCrc32OfData, dataHash));
        }
        return data.array();
    }

    /**
     * Writes a single TFRecord to the channel.
     */
    public void write(WritableByteChannel outChannel, byte[] data) throws IOException {
        int maskedCrc32OfLength = hashLong(data.length);
        int maskedCrc32OfData = hashBytes(data);

        // Thread-safe: create new ByteBuffer instead of reusing
        ByteBuffer header = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(data.length).putInt(maskedCrc32OfLength);
        header.rewind();
        writeFully(outChannel, header);

        writeFully(outChannel, ByteBuffer.wrap(data));

        // Thread-safe: create new ByteBuffer instead of reusing
        ByteBuffer footer = ByteBuffer.allocate(FOOTER_LEN).order(ByteOrder.LITTLE_ENDIAN);
        footer.putInt(maskedCrc32OfData);
        footer.rewind();
        writeFully(outChannel, footer);
    }

    /**
     * Writes a TFRecord directly to a ByteBuffer.
     * This is a convenience method for the TFRecordWriter.
     */
    @VisibleForTesting
    void writeTfRecord(ByteBuffer buffer, byte[] data) {
        if (buffer.remaining() < recordLength(data)) {
            throw new IllegalArgumentException("Buffer does not have enough capacity for record");
        }

        int maskedCrc32OfLength = hashLong(data.length);
        int maskedCrc32OfData = hashBytes(data);

        // Ensure little endian byte order but preserve original order
        ByteOrder originalOrder = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        try {
            // Write header: length + length CRC
            buffer.putLong(data.length).putInt(maskedCrc32OfLength);

            // Write data
            buffer.put(data);

            // Write footer: data CRC
            buffer.putInt(maskedCrc32OfData);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @VisibleForTesting
    static void readFully(ReadableByteChannel in, ByteBuffer bb) throws IOException {
        int expected = bb.remaining();
        int actual = read(in, bb);
        if (expected != actual) {
            throw new IOException(String.format("expected %d, but got %d", expected, actual));
        }
    }

    private static int read(ReadableByteChannel in, ByteBuffer bb) throws IOException {
        int expected = bb.remaining();
        while (bb.hasRemaining() && in.read(bb) >= 0) {
            // Continue reading until buffer is full or end of channel
        }
        return expected - bb.remaining();
    }

    @VisibleForTesting
    static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}