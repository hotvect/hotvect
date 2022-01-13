package com.hotvect.vw.util;

import com.hotvect.vw.VwModelImporter;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VwModelImporterTest {

    @Test
    void importModel() {
        VwModelImporter subject = new VwModelImporter();
        InputStreamReader model = new InputStreamReader(this.getClass().getResourceAsStream("test.model"), StandardCharsets.UTF_8);
        Int2DoubleMap read = subject.apply(new BufferedReader(model));
        Map<Integer, Double> expected = new HashMap<>();
        expected.put(0,1.0);
        expected.put(1,12.0);
        expected.put(2,0.01);
        expected.put(3,-1.0);
        expected.put(4,-13.0);
        expected.put(5,-0.1);
        expected.put(6,1.0);
        expected.put(7,-1.0);
        expected.put(8,2e9);
        expected.put(9,+2E+09);
        expected.put(10,+2E-09);
        expected.put(11,-2e-9);
        expected.put(12,8.66143e-06);
        expected.put(13,0.0);
        expected.put(14,0.0);


        assertEquals(expected, read.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

    }

    @Test
    void emptyModel() {
        VwModelImporter subject = new VwModelImporter();
        InputStreamReader model = new InputStreamReader(this.getClass().getResourceAsStream("empty.model"), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> subject.apply(new BufferedReader(model)));
    }

    @Test
    void corruptedModel() {
        VwModelImporter subject = new VwModelImporter();
        InputStreamReader model = new InputStreamReader(this.getClass().getResourceAsStream("corrupted.model"), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> subject.apply(new BufferedReader(model)));
    }


}