package com.hotvect.onlineutils.concurrency.fileutils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;

/**
 * RecordReader implementation for text-based formats (txt, json, jsonl, csv, tsv).
 * Reads files line by line, returning each line as a String.
 * Automatically handles gzip compression (.gz extension).
 */
class TextRecordReader implements RecordReader<String> {
    private final BufferedReader reader;
    private String nextLine;

    public TextRecordReader(File file) {
        this.reader = FileUtils.toBufferedReader(file);
        try {
            this.nextLine = reader.readLine();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read first line from file: " + file, e);
        }
    }

    @Override
    public boolean hasNext() {
        return nextLine != null;
    }

    @Override
    public String next() {
        try {
            String current = nextLine;
            nextLine = reader.readLine();
            return current;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read next line", e);
        }
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close reader", e);
        }
    }
}
