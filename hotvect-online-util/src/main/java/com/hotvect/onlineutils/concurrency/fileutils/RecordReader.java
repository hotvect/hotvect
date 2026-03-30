package com.hotvect.onlineutils.concurrency.fileutils;

import java.io.File;
import java.util.Iterator;

/**
 * Interface for reading records from files.
 * Abstracts over different file formats (text, Avro, etc.)
 * Implements Iterator to provide standard iteration semantics.
 * IOExceptions are wrapped in RuntimeException as per Iterator contract.
 *
 * @param <T> the type of record to read
 */
public interface RecordReader<T> extends Iterator<T>, AutoCloseable {

    /**
     * Creates a RecordReader for the given file, auto-detecting the format.
     *
     * @param file the file to read
     * @param <T> the record type
     * @return a RecordReader instance
     */
    @SuppressWarnings("unchecked")
    static <T> RecordReader<T> create(File file) {
        FileFormat format = FileFormat.detectFormat(file);
        return switch (format) {
            case TEXT -> (RecordReader<T>) new TextRecordReader(file);
            case AVRO -> (RecordReader<T>) new AvroRecordReader(file);
        };
    }
}
