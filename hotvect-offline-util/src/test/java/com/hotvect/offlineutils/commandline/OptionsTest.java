package com.hotvect.offlineutils.commandline;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

import java.io.File;
import java.util.List;
import java.util.Map;

class OptionsTest {

    @Test
    void testCommaSeparatedSource() {
        Options options = new Options();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "file1.txt,file2.txt,dir1");
        Map<String, List<File>> sourceFiles = options.sourceFiles;

        Assertions.assertEquals(1, sourceFiles.size());
        Assertions.assertTrue(sourceFiles.containsKey("default"));
        Assertions.assertEquals(3, sourceFiles.get("default").size());
        Assertions.assertEquals("file1.txt", sourceFiles.get("default").get(0).getName());
        Assertions.assertEquals("file2.txt", sourceFiles.get("default").get(1).getName());
        Assertions.assertEquals("dir1", sourceFiles.get("default").get(2).getName());
    }

    @Test
    void testJsonSource() {
        Options options = new Options();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "{\"json\":[\"file1.json\",\"file2.json\"],\"csv\":[\"data1.csv\",\"data2.csv\"]}");
        Map<String, List<File>> sourceFiles = options.sourceFiles;

        Assertions.assertEquals(2, sourceFiles.size());
        Assertions.assertTrue(sourceFiles.containsKey("json"));
        Assertions.assertTrue(sourceFiles.containsKey("csv"));
        Assertions.assertEquals(2, sourceFiles.get("json").size());
        Assertions.assertEquals(2, sourceFiles.get("csv").size());
        Assertions.assertEquals("file1.json", sourceFiles.get("json").get(0).getName());
        Assertions.assertEquals("file2.json", sourceFiles.get("json").get(1).getName());
        Assertions.assertEquals("data1.csv", sourceFiles.get("csv").get(0).getName());
        Assertions.assertEquals("data2.csv", sourceFiles.get("csv").get(1).getName());
    }

    @Test
    void testInvalidJsonFormat() {
        Options options = new Options();
        CommandLine cmd = new CommandLine(options);

        Assertions.assertThrows(ParameterException.class, () ->
                cmd.parseArgs("--source", "{invalid_json}")
        );
    }

    @Test
    void testDefaultTypeNameInJsonIsAllowed() {
        Options options = new Options();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "{\"default\":[\"file1.txt\",\"file2.txt\"]}");
        Map<String, List<File>> sourceFiles = options.sourceFiles;

        Assertions.assertEquals(1, sourceFiles.size());
        Assertions.assertTrue(sourceFiles.containsKey("default"));
        Assertions.assertEquals(2, sourceFiles.get("default").size());
        Assertions.assertEquals("file1.txt", sourceFiles.get("default").get(0).getName());
        Assertions.assertEquals("file2.txt", sourceFiles.get("default").get(1).getName());
    }

    @Test
    void testMultipleSourceOptionsThrowsException() {
        Options options = new Options();
        CommandLine cmd = new CommandLine(options);

        Assertions.assertThrows(ParameterException.class, () ->
                cmd.parseArgs("--source", "file1.txt", "--source", "file2.txt")
        );
    }

    @Test
    void testJsonArraySource() {
        Options options = new Options();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "[\"file1.txt\",\"file2.txt\",\"dir1\"]");
        Map<String, List<File>> sourceFiles = options.sourceFiles;

        Assertions.assertEquals(1, sourceFiles.size());
        Assertions.assertTrue(sourceFiles.containsKey("default"));
        Assertions.assertEquals(3, sourceFiles.get("default").size());
        Assertions.assertEquals("file1.txt", sourceFiles.get("default").get(0).getName());
        Assertions.assertEquals("file2.txt", sourceFiles.get("default").get(1).getName());
        Assertions.assertEquals("dir1", sourceFiles.get("default").get(2).getName());
    }
}