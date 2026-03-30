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
        Main.SourceFilesOption options = new Main.SourceFilesOption();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "file1.txt,file2.txt,dir1");
        Map<String, List<File>> sourceFiles = options.sourceFiles.files;

        Assertions.assertEquals(1, sourceFiles.size());
        Assertions.assertTrue(sourceFiles.containsKey("default"));
        Assertions.assertEquals(3, sourceFiles.get("default").size());
        Assertions.assertEquals("file1.txt", sourceFiles.get("default").get(0).getName());
        Assertions.assertEquals("file2.txt", sourceFiles.get("default").get(1).getName());
        Assertions.assertEquals("dir1", sourceFiles.get("default").get(2).getName());
    }

    @Test
    void testJsonObjectSource() {
        Main.SourceFilesOption options = new Main.SourceFilesOption();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "{\"json\":[\"file1.json\",\"file2.json\"],\"csv\":[\"data1.csv\",\"data2.csv\"]}");
        Map<String, List<File>> sourceFiles = options.sourceFiles.files;

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
        Main.SourceFilesOption options = new Main.SourceFilesOption();
        CommandLine cmd = new CommandLine(options);

        Assertions.assertThrows(ParameterException.class, () ->
                cmd.parseArgs("--source", "{\"invalid_json")
        );
    }

    @Test
    void testJsonArraySource() {
        Main.SourceFilesOption options = new Main.SourceFilesOption();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "[\"file1.txt\",\"file2.txt\"]");
        Map<String, List<File>> sourceFiles = options.sourceFiles.files;

        Assertions.assertEquals(1, sourceFiles.size());
        Assertions.assertTrue(sourceFiles.containsKey("default"));
        Assertions.assertEquals(2, sourceFiles.get("default").size());
        Assertions.assertEquals("file1.txt", sourceFiles.get("default").get(0).getName());
        Assertions.assertEquals("file2.txt", sourceFiles.get("default").get(1).getName());
    }

    @Test
    void testMultipleSourceOptionsThrowsException() {
        Main.SourceFilesOption options = new Main.SourceFilesOption();
        CommandLine cmd = new CommandLine(options);

        Assertions.assertThrows(ParameterException.class, () ->
                cmd.parseArgs("--source", "file1.txt", "--source", "file2.txt")
        );
    }

    @Test
    void testSingleKeyJsonSource() {
        Main.SourceFilesOption options = new Main.SourceFilesOption();
        CommandLine cmd = new CommandLine(options);

        cmd.parseArgs("--source", "{\"training\":[\"file1.txt\",\"file2.txt\",\"dir1\"]}");
        Map<String, List<File>> sourceFiles = options.sourceFiles.files;

        Assertions.assertEquals(1, sourceFiles.size());
        Assertions.assertTrue(sourceFiles.containsKey("training"));
        Assertions.assertEquals(3, sourceFiles.get("training").size());
        Assertions.assertEquals("file1.txt", sourceFiles.get("training").get(0).getName());
        Assertions.assertEquals("file2.txt", sourceFiles.get("training").get(1).getName());
        Assertions.assertEquals("dir1", sourceFiles.get("training").get(2).getName());
    }

    @Test
    void testWriterNumShardsDefaultValue() {
        Main.EncodeCommand encode = new Main.EncodeCommand();
        CommandLine cmd = new CommandLine(encode);
        cmd.parseArgs(
                "--algorithm-jar", "algo.jar",
                "--algorithm-definition", "algo",
                "--parameters", "params.zip",
                "--source", "file1.txt",
                "--dest", "encoded"
        );

        Assertions.assertEquals(-1, encode.writerNumShards);
    }

    @Test
    void testWriterNumShardsExplicitValue() {
        Main.EncodeCommand encode = new Main.EncodeCommand();
        CommandLine cmd = new CommandLine(encode);
        cmd.parseArgs(
                "--algorithm-jar", "algo.jar",
                "--algorithm-definition", "algo",
                "--parameters", "params.zip",
                "--source", "file1.txt",
                "--dest", "encoded",
                "--writer-num-shards", "8"
        );

        Assertions.assertEquals(8, encode.writerNumShards);
    }

    @Test
    void testWriterNumShardsZero() {
        Main.EncodeCommand encode = new Main.EncodeCommand();
        CommandLine cmd = new CommandLine(encode);
        cmd.parseArgs(
                "--algorithm-jar", "algo.jar",
                "--algorithm-definition", "algo",
                "--parameters", "params.zip",
                "--source", "file1.txt",
                "--dest", "encoded",
                "--writer-num-shards", "0"
        );

        Assertions.assertEquals(0, encode.writerNumShards);
    }

    @Test
    void testWriterNumShardsNegative() {
        Main.EncodeCommand encode = new Main.EncodeCommand();
        CommandLine cmd = new CommandLine(encode);
        cmd.parseArgs(
                "--algorithm-jar", "algo.jar",
                "--algorithm-definition", "algo",
                "--parameters", "params.zip",
                "--source", "file1.txt",
                "--dest", "encoded",
                "--writer-num-shards", "-1"
        );

        Assertions.assertEquals(-1, encode.writerNumShards);
    }
}
