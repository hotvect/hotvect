package com.eshioji.hotvect.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import org.anarres.parallelgzip.ParallelGZIPOutputStream;

import java.io.*;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

public class SerializationUtils {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static long serializeToFile(File dest, Map<String, String> toSerialize) throws FileNotFoundException {
            String ext = Files.getFileExtension(dest.toPath().getFileName().toString());
            boolean isDestGzip = "gz".equalsIgnoreCase(ext);

            try (FileOutputStream file = new FileOutputStream(dest);
                 OutputStream sink = isDestGzip ? new GZIPOutputStream(file) : file;
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sink, Charsets.UTF_8), 65536)
            ) {
                OBJECT_MAPPER.writeValue(writer, toSerialize);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return dest.length();
    }
}
