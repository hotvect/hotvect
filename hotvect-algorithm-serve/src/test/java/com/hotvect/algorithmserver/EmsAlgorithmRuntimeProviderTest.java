package com.hotvect.algorithmserver;

import com.google.common.util.concurrent.AbstractService;
import com.hotvect.onlineutils.experimentmanagement.experimentation.ExperimentationManager;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmsAlgorithmRuntimeProviderTest {
    @Test
    void closeClosesOwnedResources() {
        RecordingExperimentationManager experimentationManager = new RecordingExperimentationManager();
        RecordingCloseable emsClient = new RecordingCloseable();
        RecordingCloseable downloadClient = new RecordingCloseable();

        EmsAlgorithmRuntimeProvider provider = new EmsAlgorithmRuntimeProvider(
                URI.create("https://ems.example"),
                "catalog",
                "customer-1",
                Path.of("/tmp/hv-serve-ems-test"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                "EMS_TOKEN",
                emsClient,
                downloadClient,
                experimentationManager
        );

        provider.close();

        assertTrue(experimentationManager.closed);
        assertTrue(emsClient.closed);
        assertTrue(downloadClient.closed);
    }

    private static final class RecordingCloseable implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingExperimentationManager extends AbstractService
            implements ExperimentationManager {
        private boolean closed;

        @Override
        public Set<String> slotNames() {
            return Set.of("catalog");
        }

        @Override
        public VariantConfiguration assignVariant(String slotName, String assignmentKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExperimentationState currentState(String slotName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int totalNumberOfShards(String slotName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refreshNow(String slotName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refreshAllNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        protected void doStart() {
            notifyStarted();
        }

        @Override
        protected void doStop() {
            notifyStopped();
        }
    }
}
