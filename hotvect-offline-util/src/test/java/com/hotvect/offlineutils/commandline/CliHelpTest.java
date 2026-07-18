package com.hotvect.offlineutils.commandline;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliHelpTest {
    @Test
    void includeFeatureStoreResponsesFlagIsOnlyShownOnPredictAndAudit() {
        CommandLine cmd = new CommandLine(new Main.RootCommand());

        assertFalse(cmd.getUsageMessage().contains("--include-feature-store-responses"));
        assertEquals(Set.of("encode", "predict", "audit", "generate-state", "performance-test"), cmd.getSubcommands().keySet());

        assertTrue(cmd.getSubcommands().get("predict").getUsageMessage().contains("--include-feature-store-responses"));
        assertTrue(cmd.getSubcommands().get("audit").getUsageMessage().contains("--include-feature-store-responses"));

        assertFalse(cmd.getSubcommands().get("encode").getUsageMessage().contains("--include-feature-store-responses"));
        assertFalse(cmd.getSubcommands().get("generate-state").getUsageMessage().contains("--include-feature-store-responses"));
        assertFalse(cmd.getSubcommands().get("performance-test").getUsageMessage().contains("--include-feature-store-responses"));
    }
}
