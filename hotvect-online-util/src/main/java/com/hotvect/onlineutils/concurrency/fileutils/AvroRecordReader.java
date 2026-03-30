package com.hotvect.onlineutils.concurrency.fileutils;

import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;

import java.io.File;
import java.io.IOException;

/**
 * RecordReader implementation for Apache Avro binary format (.avro files).
 * Reads Avro records as GenericRecord objects.
 * Automatically handles Avro's built-in compression (Snappy, Deflate, etc.).
 */
class AvroRecordReader implements RecordReader<GenericRecord> {
    private final DataFileReader<GenericRecord> reader;

    public AvroRecordReader(File file) {
        try {
            // DataFileReader automatically detects and handles compression codec
            // from the Avro file metadata (Snappy, Deflate, etc.)
            this.reader = new DataFileReader<>(file, new GenericDatumReader<>());
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Avro file: " + file, e);
        }
    }

    @Override
    public boolean hasNext() {
        return reader.hasNext();
    }

    @Override
    public GenericRecord next() {
        return reader.next();
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close Avro reader", e);
        }
    }
}
