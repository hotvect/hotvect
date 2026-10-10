package com.hotvect.offlineutils.commandline;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

class OfflineAlgorithmSourceTest {

    @Test
    void directSourceExpandsTildePaths() {
        OfflineAlgorithmSource.Direct source = new OfflineAlgorithmSource.Direct(
                tildePath("algorithm.jar"),
                "algorithm",
                List.of(tildePath("domain-model.jar")),
                List.of(tildePath("additional.jar")),
                tildePath("parameters.zip"));

        Assertions.assertEquals(homePath("algorithm.jar"), source.algorithmJar());
        Assertions.assertEquals(List.of(homePath("domain-model.jar")), source.domainModelJars());
        Assertions.assertEquals(List.of(homePath("additional.jar")), source.additionalJars());
        Assertions.assertEquals(homePath("parameters.zip"), source.parameters());
    }

    @Test
    void fixedSourceExpandsTildePaths() {
        OfflineAlgorithmSource.Fixed source = new OfflineAlgorithmSource.Fixed(
                tildePath("composition.json"),
                List.of(tildePath("domain-model.jar")));

        Assertions.assertEquals(homePath("composition.json"), source.composition());
        Assertions.assertEquals(List.of(homePath("domain-model.jar")), source.domainModelJars());
    }

    @Test
    void emsSourceExpandsTildePaths() {
        OfflineAlgorithmSource.Ems source = new OfflineAlgorithmSource.Ems(
                "root-slot",
                tildePath("ems-state.json"),
                "/user/id",
                List.of(tildePath("domain-model.jar")));

        Assertions.assertEquals(homePath("ems-state.json"), source.state());
        Assertions.assertEquals(List.of(homePath("domain-model.jar")), source.domainModelJars());
    }

    private static File tildePath(String name) {
        return new File("~" + File.separator + name);
    }

    private static File homePath(String name) {
        return new File(System.getProperty("user.home"), name);
    }
}
