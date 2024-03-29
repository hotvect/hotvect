package com.hotvect.util;

import com.codahale.metrics.MetricRegistry;
import com.hotvect.core.util.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpuIntensiveFileMapperTest {

    @Test
    void normalFile() throws Exception {
        File source = getAsFile("example.jsons");
        test(source);
    }

    @Test
    void gzippedFile() throws Exception {
        File source = getAsFile("example.jsons.gz");
        test(source);
    }

    private void test(File source) throws IOException {
        MetricRegistry mr = new MetricRegistry();

        Function<String, List<String>> fun = s ->
                ImmutableList.of(String.valueOf(Hashing.sha512().hashString(s, StandardCharsets.UTF_8).asInt()));
        File dest = getTempFile();
        try {
            CpuIntensiveFileMapper subject = CpuIntensiveFileMapper.mapper(mr, source, dest, fun);
            subject.run();
            try (BufferedReader original = getAsReader("example.jsons");
                 BufferedReader processed = getAsReader(dest)) {
                StreamUtils.zip(original.lines(), processed.lines(), (original1, actual) -> {
                    int expected = Hashing.sha512().hashString(original1, StandardCharsets.UTF_8).asInt();
                    Integer actualOut = Integer.valueOf(actual);
                    return Pair.of(expected, actualOut);
                }).forEach(p -> assertEquals(p._1, p._2));
            }
        } finally {
            dest.delete();
        }
    }

    private File getTempFile() {
        try {
            return Files.createTempFile(this.getClass().getCanonicalName() + "-test", "tmp").toFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BufferedReader getAsReader(File file) {
        try {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    private BufferedReader getAsReader(String name) {
        return new BufferedReader(new InputStreamReader(this.getClass().getResourceAsStream(name), StandardCharsets.UTF_8));
    }

    private File getAsFile(String name) {
        try {
            return Paths.get(this.getClass().getResource(name).toURI()).toFile();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}