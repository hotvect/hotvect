package com.eshioji.hotvect.hotdeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloseableJarLoaderTest {
    @Test
    public void check() throws Exception {
        var data = new AlgorithmMetadata("name", "instanceId", "exdec", "exenc", "exsco");
        System.out.println(new ObjectMapper().writeValueAsString(data));
    }

}