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
        assertEquals(
                Set.of("encode", "predict", "audit", "generate-state", "performance-test", "ems-snapshot-export"),
                cmd.getSubcommands().keySet());

        assertTrue(cmd.getSubcommands().get("predict").getUsageMessage().contains("--include-feature-store-responses"));
        assertTrue(cmd.getSubcommands().get("audit").getUsageMessage().contains("--include-feature-store-responses"));

        assertFalse(cmd.getSubcommands().get("encode").getUsageMessage().contains("--include-feature-store-responses"));
        assertFalse(cmd.getSubcommands().get("generate-state").getUsageMessage().contains("--include-feature-store-responses"));
        assertFalse(cmd.getSubcommands().get("performance-test").getUsageMessage().contains("--include-feature-store-responses"));
    }

    @Test
    void predictionAcceptsOnlyPinnedEmsStateAndExportOwnsLiveEmsOptions() {
        CommandLine cmd = new CommandLine(new Main.RootCommand());

        String predictUsage = cmd.getSubcommands().get("predict").getUsageMessage();
        assertTrue(predictUsage.contains("--ems-state"));
        assertFalse(predictUsage.contains("--ems-uri"));
        assertFalse(predictUsage.contains("--ems-connect-timeout-seconds"));
        assertFalse(predictUsage.contains("--ems-read-timeout-seconds"));

        String exportUsage = cmd.getSubcommands().get("ems-snapshot-export").getUsageMessage();
        assertTrue(exportUsage.contains("--ems-uri"));
        assertTrue(exportUsage.contains("--ems-connect-timeout-seconds"));
        assertTrue(exportUsage.contains("--ems-read-timeout-seconds"));
    }

    @Test
    void performanceTestAcceptsTheSamePinnedCompositionSourcesAsPredict() {
        CommandLine cmd = new CommandLine(new Main.RootCommand());

        String predictUsage = cmd.getSubcommands().get("predict").getUsageMessage();
        String usage = cmd.getSubcommands().get("performance-test").getUsageMessage();

        assertTrue(predictUsage.contains("--domain-model-jar"));
        assertTrue(usage.contains("--composition"));
        assertTrue(usage.contains("--ems-slot"));
        assertTrue(usage.contains("--ems-state"));
        assertTrue(usage.contains("--assignment-key-json-pointer"));
        assertTrue(usage.contains("--domain-model-jar"));
    }
}
