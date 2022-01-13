package com.hotvect.util;

import com.hotvect.onlineutils.hotdeploy.ZipFiles;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.zip.ZipFile;

class ZipFilesTest {

    @Test
    void parameters() {
        try(ZipFile testZip = new ZipFile(this.getClass().getResource("resources.zip").getFile())){
            Map<String, InputStream> params = ZipFiles.parameters(testZip);
            for (InputStream inputStream : params.values()) {
                System.out.println(CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8)));

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}