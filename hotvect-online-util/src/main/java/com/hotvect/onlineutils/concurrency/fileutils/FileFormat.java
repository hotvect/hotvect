package com.hotvect.onlineutils.concurrency.fileutils;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enum representing different file formats supported by the file processing utilities.
 */
public enum FileFormat {
    /**
     * Text-based formats (line-delimited: txt, json, jsonl, jsons, csv, tsv).
     * Records are read as String, one line per record.
     * Supports gzip compression (.gz extension).
     */
    TEXT(ImmutableSet.of(".txt", ".json", ".jsonl", ".jsons", ".csv", ".tsv",
                         ".txt.gz", ".json.gz", ".jsonl.gz", ".jsons.gz", ".csv.gz", ".tsv.gz")),

    /**
     * Apache Avro binary format (.avro files).
     * Records are read as org.apache.avro.generic.GenericRecord.
     * Avro files have built-in compression support (including Snappy).
     */
    AVRO(ImmutableSet.of(".avro"));

    private final ImmutableSet<String> extensions;

    FileFormat(ImmutableSet<String> extensions) {
        this.extensions = extensions;
    }

    /**
     * Detects the file format based on the file extension.
     *
     * @param file the file to detect the format for
     * @return the detected FileFormat
     * @throws IllegalArgumentException if the file extension is not recognized
     */
    public static FileFormat detectFormat(File file) {
        String fileName = file.getName().toLowerCase();

        if (isExtensionlessSparkTextPartFile(fileName)) {
            return TEXT;
        }

        for (FileFormat format : FileFormat.values()) {
            for (String extension : format.extensions) {
                if (fileName.endsWith(extension)) {
                    return format;
                }
            }
        }

        throw new IllegalArgumentException("Unsupported file format: " + file.getName());
    }

    static boolean isSupportedFileName(String fileName) {
        String lowerCaseFileName = fileName.toLowerCase();
        if (isExtensionlessSparkTextPartFile(lowerCaseFileName)) {
            return true;
        }

        for (FileFormat format : FileFormat.values()) {
            for (String extension : format.extensions) {
                if (lowerCaseFileName.endsWith(extension)) {
                    return true;
                }
            }
        }

        return false;
    }

    static boolean isExtensionlessSparkTextPartFile(String fileName) {
        if (!fileName.startsWith("part-")) {
            return false;
        }

        String uncompressedFileName = fileName.endsWith(".gz") ? fileName.substring(0, fileName.length() - 3) : fileName;
        return uncompressedFileName.length() > "part-".length() && !uncompressedFileName.contains(".");
    }

    /**
     * Validates that all files in the list have the same format.
     *
     * @param files the list of files to validate
     * @return the common FileFormat
     * @throws IllegalArgumentException if files have mixed formats
     */
    public static FileFormat validateUniformFormat(List<File> files) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("File list cannot be empty");
        }

        Set<FileFormat> formats = files.stream()
                .map(FileFormat::detectFormat)
                .collect(Collectors.toSet());

        if (formats.size() > 1) {
            throw new IllegalArgumentException(
                "Mixed file formats detected. All files must have the same format. Found: " + formats
            );
        }

        return formats.iterator().next();
    }

    public ImmutableSet<String> getExtensions() {
        return extensions;
    }
}
