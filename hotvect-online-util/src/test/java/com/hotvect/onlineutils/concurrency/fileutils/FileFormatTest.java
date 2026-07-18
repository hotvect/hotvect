package com.hotvect.onlineutils.concurrency.fileutils;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileFormatTest {

    @Test
    void detectTextFormats() throws IOException {
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.txt")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.json")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.jsonl")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.csv")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.tsv")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.txt.gz")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.json.gz")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("test.jsonl.gz")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("part-00000")));
        assertEquals(FileFormat.TEXT, FileFormat.detectFormat(new File("part-00000.gz")));
    }

    @Test
    void detectAvroFormat() {
        assertEquals(FileFormat.AVRO, FileFormat.detectFormat(new File("test.avro")));
        assertEquals(FileFormat.AVRO, FileFormat.detectFormat(new File("data.AVRO")));
    }

    @Test
    void detectUnsupportedFormat() {
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.detectFormat(new File("test.xml")));
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.detectFormat(new File("test.parquet")));
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.detectFormat(new File("part-")));
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.detectFormat(new File("part-.gz")));
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.detectFormat(new File("part-00000.parquet")));
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.detectFormat(new File("part-00000.parquet.gz")));
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.detectFormat(new File("test.xml")));
    }

    @Test
    void validateUniformTextFormat() throws IOException {
        File file1 = Files.createTempFile("test1", ".json").toFile();
        File file2 = Files.createTempFile("test2", ".jsonl.gz").toFile();
        File file3 = Files.createTempFile("test3", ".txt").toFile();

        try {
            FileFormat format = FileFormat.validateUniformFormat(List.of(file1, file2, file3));
            assertEquals(FileFormat.TEXT, format);
        } finally {
            file1.delete();
            file2.delete();
            file3.delete();
        }
    }

    @Test
    void validateUniformAvroFormat() throws IOException {
        File file1 = Files.createTempFile("test1", ".avro").toFile();
        File file2 = Files.createTempFile("test2", ".avro").toFile();

        try {
            FileFormat format = FileFormat.validateUniformFormat(List.of(file1, file2));
            assertEquals(FileFormat.AVRO, format);
        } finally {
            file1.delete();
            file2.delete();
        }
    }

    @Test
    void rejectMixedFormats() throws IOException {
        File textFile = Files.createTempFile("test", ".json").toFile();
        File avroFile = Files.createTempFile("test", ".avro").toFile();

        try {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    FileFormat.validateUniformFormat(List.of(textFile, avroFile)));
            assertTrue(exception.getMessage().contains("Mixed file formats"));
        } finally {
            textFile.delete();
            avroFile.delete();
        }
    }

    @Test
    void rejectEmptyFileList() {
        assertThrows(IllegalArgumentException.class, () ->
                FileFormat.validateUniformFormat(List.of()));
    }
}
