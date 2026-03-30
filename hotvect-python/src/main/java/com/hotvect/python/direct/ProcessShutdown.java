package com.hotvect.python.direct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ProcessShutdown {
    private static final Logger log = LoggerFactory.getLogger(ProcessShutdown.class);

    private ProcessShutdown() {
    }

    static void shutdown(Process process, Duration sigtermTimeout, Duration sigkillTimeout, Duration descendantsTimeout) {
        if (process == null) {
            return;
        }

        try {
            if (!process.isAlive()) {
                return;
            }

            process.destroy(); // SIGTERM
            if (process.waitFor(sigtermTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }

            log.warn("Process pid={} did not exit after SIGTERM; escalating (some worker processes may be orphaned).", safePid(process));

            terminateDescendants(process, false, descendantsTimeout);
            process.destroyForcibly(); // SIGKILL
            if (!process.waitFor(sigkillTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateDescendants(process, true, descendantsTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.warn("Failed to shutdown process pid={}", safePid(process), e);
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    private static void terminateDescendants(Process process, boolean force, Duration timeout) {
        List<ProcessHandle> descendants;
        try {
            descendants = process.toHandle().descendants().toList();
        } catch (Exception e) {
            log.debug("Failed to snapshot descendants for pid={}; may orphan worker processes.", safePid(process), e);
            return;
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (ProcessHandle ph : descendants) {
            try {
                if (force) {
                    ph.destroyForcibly();
                } else {
                    ph.destroy();
                }
            } catch (Exception ignored) {
            }
        }

        for (ProcessHandle ph : descendants) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                ph.onExit().get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private static long safePid(Process process) {
        try {
            return process.pid();
        } catch (Exception e) {
            return -1;
        }
    }
}
