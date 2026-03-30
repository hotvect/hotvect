package com.hotvect.tensorflow;

import com.google.common.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Utility class for writing multiple records to a single ByteBuffer in TFRecord format.
 *
 * This class is designed to work with HotVect's ByteBuffer-based encoder architecture
 * while producing proper TFRecord format compatible with TensorFlow's tf.data.TFRecordDataset.
 */
public class TFRecordWriter {
    private final TFRecordCodec codec;

    public TFRecordWriter() {
        this.codec = new TFRecordCodec();
    }

    @VisibleForTesting
    TFRecordWriter(TFRecordCodec codec) {
        this.codec = codec;
    }

    /**
     * Creates a single ByteBuffer containing all records in proper TFRecord format.
     *
     * Each input ByteBuffer is treated as one record and written in TFRecord format:
     * [8 bytes: length][4 bytes: length CRC][N bytes: data][4 bytes: data CRC]
     *
     * @param records list of ByteBuffers, each containing one serialized record
     * @return single ByteBuffer containing all records in TFRecord format
     */
    public ByteBuffer createTfRecordByteBuffer(List<ByteBuffer> records) {
        if (records.isEmpty()) {
            return ByteBuffer.allocate(0);
        }

        // Calculate total size needed for all records in TFRecord format
        int totalSize = 0;
        for (ByteBuffer record : records) {
            byte[] data = toByteArray(record);
            totalSize += codec.recordLength(data);
        }

        // Allocate result buffer
        ByteBuffer result = ByteBuffer.allocate(totalSize);

        // Write each record in TFRecord format
        for (ByteBuffer record : records) {
            byte[] data = toByteArray(record);
            codec.writeTfRecord(result, data);
        }

        return result.rewind();
    }

    /**
     * Creates a single-record TFRecord ByteBuffer directly from serialized record bytes.
     *
     * <p>This avoids the intermediate list/ByteBuffer adaptation used by
     * {@link #createTfRecordByteBuffer(List)} when callers only need to wrap one record.
     *
     * @param record serialized record bytes
     * @return single-record TFRecord buffer
     */
    public ByteBuffer createTfRecordByteBuffer(byte[] record) {
        ByteBuffer result = ByteBuffer.allocate(codec.recordLength(record));
        codec.writeTfRecord(result, record);
        return result.rewind();
    }

    /**
     * Converts a ByteBuffer to byte array, preserving the buffer's position and limit.
     */
    @VisibleForTesting
    byte[] toByteArray(ByteBuffer buffer) {
        // Create a duplicate to avoid modifying the original buffer
        ByteBuffer duplicate = buffer.duplicate();

        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);
        return data;
    }
}
