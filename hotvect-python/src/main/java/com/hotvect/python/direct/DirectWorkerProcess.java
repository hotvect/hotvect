package com.hotvect.python.direct;

import com.hotvect.utils.VerboseRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

final class DirectWorkerProcess implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DirectWorkerProcess.class);

    @FunctionalInterface
    interface WorkerStarter {
        Process start(
                PythonWorkerCommand command,
                Path connectUdsPath,
                int maxFrameBytes,
                int workerIndex,
                Map<String, String> envOverrides
        );
    }

    @FunctionalInterface
    interface RestartStarter {
        void start();
    }

    private final int workerIndex;
    private final String cudaVisibleDevices;
    private final int maxFrameBytes;
    private final Duration startupTimeout;
    private final Duration hungWorkerTimeout;
    private final Duration sigtermTimeout;
    private final Duration sigkillTimeout;
    private final Duration descendantsTimeout;

    private final PythonWorkerCommand pythonWorkerCommand;
    private final Path udsBaseDir;
    private final DirectWorkerManager manager;
    private final WorkerStarter workerStarter;
    private final RestartStarter restartStarter;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicReference<ConnectionHandle> connectionHandle = new AtomicReference<>();

    private volatile boolean started;
    private volatile boolean closed;

    private final ExecutorService workerExecutor;

    DirectWorkerProcess(
            int workerIndex,
            PythonWorkerCommand pythonWorkerCommand,
            DirectWorkersConfig config,
            DirectWorkerManager manager
    ) {
        this(
                workerIndex,
                pythonWorkerCommand,
                config,
                manager,
                PythonProcessLauncher::startDirectWorker,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("hv-direct-worker-" + workerIndex + "-", 0).factory()
                )
        );
    }

    DirectWorkerProcess(
            int workerIndex,
            PythonWorkerCommand pythonWorkerCommand,
            DirectWorkersConfig config,
            DirectWorkerManager manager,
            WorkerStarter workerStarter,
            ExecutorService workerExecutor
    ) {
        this(
                workerIndex,
                pythonWorkerCommand,
                config,
                manager,
                workerStarter,
                workerExecutor,
                null
        );
    }

    DirectWorkerProcess(
            int workerIndex,
            PythonWorkerCommand pythonWorkerCommand,
            DirectWorkersConfig config,
            DirectWorkerManager manager,
            WorkerStarter workerStarter,
            ExecutorService workerExecutor,
            RestartStarter restartStarter
    ) {
        this.workerIndex = workerIndex;
        this.pythonWorkerCommand = Objects.requireNonNull(pythonWorkerCommand, "pythonWorkerCommand");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.workerStarter = Objects.requireNonNull(workerStarter, "workerStarter");

        this.cudaVisibleDevices = config.cudaVisibleDevicesPerWorker().get(workerIndex).trim();
        this.maxFrameBytes = config.maxFrameBytes();
        this.startupTimeout = config.startupTimeout();
        this.hungWorkerTimeout = manager.hungWorkerTimeout();
        this.sigtermTimeout = config.shutdownSigtermTimeout();
        this.sigkillTimeout = config.shutdownSigkillTimeout();
        this.descendantsTimeout = config.shutdownDescendantsTimeout();
        this.udsBaseDir = config.udsBaseDir();

        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.restartStarter = restartStarter == null
                ? this::start
                : Objects.requireNonNull(restartStarter, "restartStarter");
    }

    private Duration restartRetryBackoff() {
        Duration cap = Duration.ofSeconds(1);
        return startupTimeout.compareTo(cap) < 0 ? startupTimeout : cap;
    }

    void start() {
        if (closed) {
            throw new IllegalStateException("Worker already closed");
        }

        boolean startRight = this.stateLock.tryLock();
        if (!startRight) {
            return;
        }

        Path udsPath = null;
        ServerSocketChannel server = null;
        SocketChannel channel = null;
        InputStream in = null;
        OutputStream out = null;
        Process process = null;
        try {
            Files.createDirectories(udsBaseDir);
            udsPath = allocateUdsPath(udsBaseDir, workerIndex);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(udsPath));

            process = workerStarter.start(
                    pythonWorkerCommand,
                    udsPath,
                    maxFrameBytes,
                    workerIndex,
                    effectiveEnv()
            );

            workerExecutor.submit(new PythonProcessLogPump(process, process.getInputStream(), "stdout"));
            workerExecutor.submit(new PythonProcessLogPump(process, process.getErrorStream(), "stderr"));

            SocketChannel accepted;
            try {
                accepted = workerExecutor.submit(server::accept).get(startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                boolean alive = process.isAlive();
                String msg = alive
                        ? "Timed out waiting for python worker to connect (startupTimeout=" + startupTimeout + "). "
                        + "The python process is still alive; python import/model load may be slow."
                        : "Timed out waiting for python worker to connect (startupTimeout=" + startupTimeout + "). "
                        + "The python process exited early (exitCode=" + safeExitCode(process) + "); check python logs above.";
                java.util.concurrent.TimeoutException te = new java.util.concurrent.TimeoutException(msg);
                te.initCause(e);
                throw te;
            }
            InputStream inToUse = Channels.newInputStream(accepted);
            OutputStream outToUse = Channels.newOutputStream(accepted);

            byte[] startupFrame = DirectIpcProtocol.readFrame(inToUse, maxFrameBytes);
            DirectIpcProtocol.DecodedMessage decoded = DirectIpcProtocol.decode(startupFrame);
            if (!(decoded instanceof DirectIpcProtocol.DecodedStartup startup)) {
                throw new IllegalStateException("Expected STARTUP from worker, got: " + decoded.getClass().getSimpleName());
            }
            long pid = startup.pid();
            byte status = startup.status();
            String message = startup.message();
            if (status == DirectIpcProtocol.STARTUP_READY) {
                // ok
            } else if (status == DirectIpcProtocol.STARTUP_CANNOT_START) {
                throw new IllegalStateException("Direct worker " + workerIndex + " pid " + pid + " reported startup failure: " + message);
            } else {
                throw new IllegalStateException(
                        "Direct worker " + workerIndex + " pid " + pid + " reported invalid startup status=" + Byte.toUnsignedInt(status)
                                + " message=" + message
                );
            }
            log.info("Direct worker {} connected (pid={})", workerIndex, pid);
            DirectIpcProtocol.writeFrame(outToUse, DirectIpcProtocol.encodeStartupAck(), maxFrameBytes);

            channel = accepted;
            in = inToUse;
            out = outToUse;

            ConnectionHandle newHandle = new ConnectionHandle(udsPath, server, accepted, inToUse, outToUse, process, pid);
            ConnectionHandle oldHandle = this.connectionHandle.getAndSet(newHandle);
            if (!this.started) {
                this.started = true;
            }
            if (oldHandle != null) {
                workerExecutor.submit(new VerboseRunnable() {
                    @Override
                    protected void doRun() throws Exception {
                        oldHandle.close();
                    }
                });
            }

            workerExecutor.submit(new ProtocolLoop(newHandle));

        } catch (Exception e) {
            log.warn("Direct worker {} failed to start; cleaning up", workerIndex, e);
            try {
                new ConnectionHandle(udsPath, server, channel, in, out, process, -1).close();
            } catch (Exception ex) {
                log.warn("Failed to close connection handle during failed start for worker {}", workerIndex, ex);
            }

            if (!started) {
                throw new RuntimeException("Direct worker " + workerIndex + " failed to start", e);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private boolean restartWorker(ConnectionHandle expectedHandle, Exception inFlightFailure) {
        if (closed || manager.isClosed()) {
            return false;
        }
        if (!connectionHandle.compareAndSet(expectedHandle, null)) {
            return false;
        }

        String inFlight = expectedHandle.inFlightRequestId;
        expectedHandle.inFlightRequestId = null;
        if (inFlight != null && inFlightFailure != null) {
            manager.failPending(inFlight, inFlightFailure);
        }

        workerExecutor.submit(new VerboseRunnable() {
            @Override
            protected void doRun() throws Exception {
                try {
                    expectedHandle.close();
                } catch (Exception e) {
                    log.debug("Failed to close worker {} during restart", workerIndex, e);
                }
                restartUntilHealthy();
            }
        });
        return true;
    }

    private void restartUntilHealthy() throws InterruptedException {
        Duration retryBackoff = restartRetryBackoff();
        while (!DirectWorkerProcess.this.closed && !manager.isClosed()) {
            if (connectionHandle.get() != null) {
                return;
            }

            restartStarter.start();
            if (connectionHandle.get() != null) {
                return;
            }

            log.warn(
                    "Direct worker {} restart did not establish a new connection; retrying in {}",
                    workerIndex,
                    retryBackoff
            );
            TimeUnit.MILLISECONDS.sleep(retryBackoff.toMillis());
        }
    }

    private final class ConnectionHandle implements AutoCloseable {
        private final AtomicBoolean closed;
        private final ReentrantLock writeLock;

        private final Path udsPath;
        private final ServerSocketChannel server;
        private final SocketChannel channel;
        private final InputStream in;
        private final OutputStream out;
        private final Process process;
        private final long pid;

        private volatile String inFlightRequestId;
        private volatile ScheduledFuture<?> hungTimeout;

        private ConnectionHandle(
                Path udsPath,
                ServerSocketChannel server,
                SocketChannel channel,
                InputStream in,
                OutputStream out,
                Process process,
                long pid
        ) {
            this.closed = new AtomicBoolean(false);
            this.writeLock = new ReentrantLock();
            this.udsPath = udsPath;
            this.server = server;
            this.channel = channel;
            this.in = in;
            this.out = out;
            this.process = process;
            this.pid = pid;
        }

        void sendFrame(byte[] payload) throws Exception {
            writeLock.lock();
            try {
                DirectIpcProtocol.writeFrame(out, payload, maxFrameBytes);
            } finally {
                writeLock.unlock();
            }
        }

        void scheduleHungTimeout(String wireRequestId) {
            cancelHungTimeout();
            ScheduledFuture<?> t = manager.scheduleTimeout(() -> {
                ConnectionHandle current = connectionHandle.get();
                if (current != ConnectionHandle.this) {
                    return;
                }
                if (closed.get() || DirectWorkerProcess.this.closed || manager.isClosed()) {
                    return;
                }
                if (!wireRequestId.equals(inFlightRequestId)) {
                    return;
                }

                log.warn(
                        "Direct worker {} pid {} exceeded hungWorkerTimeout={}, restarting",
                        workerIndex,
                        safePid(process),
                        hungWorkerTimeout
                );
                restartWorker(
                        ConnectionHandle.this,
                        new java.util.concurrent.TimeoutException("Direct worker request timed out (hung worker): " + wireRequestId)
                );
            }, hungWorkerTimeout);
            this.hungTimeout = t;
        }

        void cancelHungTimeout() {
            ScheduledFuture<?> t = hungTimeout;
            if (t == null) {
                return;
            }
            hungTimeout = null;
            try {
                t.cancel(false);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            cancelHungTimeout();

            stateLock.lock();
            boolean writeLocked = writeLock.tryLock(100, TimeUnit.MILLISECONDS);
            try {
                if (writeLocked && out != null) {
                    try {
                        DirectIpcProtocol.writeFrame(out, DirectIpcProtocol.encodeShutdown(), maxFrameBytes);
                    } catch (Exception ignored) {
                    }
                }

                try {
                    if (channel != null) {
                        channel.close();
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (server != null) {
                        server.close();
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (process != null) {
                        ProcessShutdown.shutdown(process, sigtermTimeout, sigkillTimeout, descendantsTimeout);
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (udsPath != null) {
                        Files.deleteIfExists(udsPath);
                    }
                } catch (Exception ignored) {
                }
            } finally {
                if (writeLocked) {
                    writeLock.unlock();
                }
                stateLock.unlock();
            }
        }
    }

    private final class ProtocolLoop extends VerboseRunnable {
        private final ConnectionHandle handle;

        private ProtocolLoop(ConnectionHandle connectionHandle) {
            this.handle = connectionHandle;
        }

        private void clearInFlightAndCancelHungTimeout(String receivedRequestId, String messageType) {
            String inFlight = handle.inFlightRequestId;
            handle.inFlightRequestId = null;
            handle.cancelHungTimeout();
            if (inFlight != null && !inFlight.equals(receivedRequestId)) {
                log.warn(
                        "Worker {} pid {} returned {} for requestId={} but inFlightRequestId={}",
                        workerIndex,
                        safePid(handle.process),
                        messageType,
                        receivedRequestId,
                        inFlight
                );
            }
        }

        @Override
        protected void doRun() throws Exception {
            boolean expectingGetWork = true;

            try {
                while (!DirectWorkerProcess.this.closed && !handle.closed.get()) {
                    byte[] frame = DirectIpcProtocol.readFrame(handle.in, maxFrameBytes);
                    DirectIpcProtocol.DecodedMessage decoded = DirectIpcProtocol.decode(frame);

                    switch (decoded) {
                        case DirectIpcProtocol.DecodedGetWork ignored -> {
                            if (!expectingGetWork) {
                                throw new IllegalStateException("Unexpected GET_WORK while awaiting RESULT");
                            }
                            if (manager.isClosed()) {
                                handle.sendFrame(DirectIpcProtocol.encodeShutdown());
                                return;
                            }

                            DirectWorkerManager.QueuedRequest req;
                            while (true) {
                                req = manager.waitForWorkOrNullWhenClosed(
                                        () -> DirectWorkerProcess.this.closed || handle.closed.get()
                                );
                                if (req == null) {
                                    if (manager.isClosed() && !handle.closed.get()) {
                                        try {
                                            handle.sendFrame(DirectIpcProtocol.encodeShutdown());
                                        } catch (Exception ignored1) {
                                        }
                                    }
                                    return;
                                }
                                if (manager.markDispatched(workerIndex, req)) {
                                    break;
                                }
                            }

                            expectingGetWork = false;
                            handle.inFlightRequestId = req.wireRequestId;
                            handle.scheduleHungTimeout(req.wireRequestId);
                            handle.sendFrame(req.workPayload);
                        }
                        case DirectIpcProtocol.DecodedResult result -> {
                            if (expectingGetWork) {
                                throw new IllegalStateException("Unexpected RESULT while awaiting GET_WORK");
                            }
                            expectingGetWork = true;

                            clearInFlightAndCancelHungTimeout(result.requestId(), "RESULT");
                            manager.completeResult(result.requestId(), result.output(), result.debugJson());
                        }
                        case DirectIpcProtocol.DecodedRequestError requestError -> {
                            if (expectingGetWork) {
                                throw new IllegalStateException("Unexpected REQUEST_ERROR while awaiting GET_WORK");
                            }
                            expectingGetWork = true;

                            clearInFlightAndCancelHungTimeout(requestError.requestId(), "REQUEST_ERROR");
                            manager.completeRequestError(requestError.requestId(), requestError.message());
                        }
                        case DirectIpcProtocol.DecodedWorkerError workerError -> {
                            throw new IllegalStateException("Worker error: " + workerError.message());
                        }
                        case DirectIpcProtocol.DecodedStartup ignored -> throw new IllegalStateException(
                                "Unexpected IPC message: " + decoded.getClass().getSimpleName()
                        );
                    }
                }
            } catch (Exception e) {
                if (!DirectWorkerProcess.this.closed && !handle.closed.get() && !manager.isClosed()) {
                    log.warn("Worker {} pid {} protocol loop error, restarting", workerIndex, safePid(handle.process), e);
                }

                String inFlight = handle.inFlightRequestId;
                String msg = e.getMessage();
                String suffix = (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
                restartWorker(
                        handle,
                        inFlight == null
                                ? null
                                : new DirectWorkerFailedException(
                                "Direct worker " + workerIndex + " pid " + safePid(handle.process) + " failed: " + suffix,
                                e
                        )
                );
            }
        }
    }

    private final class PythonProcessLogPump extends VerboseRunnable {
        private final Process process;
        private final InputStream stream;
        private final String streamName;

        private PythonProcessLogPump(Process process, InputStream stream, String streamName) {
            this.process = Objects.requireNonNull(process, "process");
            this.stream = Objects.requireNonNull(stream, "stream");
            this.streamName = Objects.requireNonNull(streamName, "streamName");
        }

        @Override
        protected void doRun() throws Exception {
            try (var br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                long pid = safePid(process);
                while ((line = br.readLine()) != null) {
                    log.info("[py-worker-{}, pid{}, {}] {}", workerIndex, pid, streamName, line);
                }
            } catch (Exception e) {
                log.debug("Log pump {} for worker {}, pid {} exited with error", streamName, workerIndex, safePid(process), e);
            }
        }
    }

    private static Path allocateUdsPath(Path baseDir, int workerIndex) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String name = "hv-direct-worker-" + ProcessHandle.current().pid() + "-" + workerIndex + "-" + suffix + ".sock";
        return baseDir.resolve(name);
    }

    private Map<String, String> effectiveEnv() {
        verifyCudaVisibleDevicesWithinParentAllowlist(cudaVisibleDevices, System.getenv("CUDA_VISIBLE_DEVICES"));
        var copy = new java.util.HashMap<>(pythonWorkerCommand.environment());
        copy.put("CUDA_VISIBLE_DEVICES", cudaVisibleDevices);
        return copy;
    }

    static void verifyCudaVisibleDevicesWithinParentAllowlist(String cudaVisibleDevices, String parentCudaVisibleDevicesEnv) {
        if (parentCudaVisibleDevicesEnv == null) {
            return;
        }

        if (cudaVisibleDevices == null || cudaVisibleDevices.isBlank()) {
            return;
        }

        String worker = cudaVisibleDevices.trim();
        List<String> allowlist = CudaDeviceUtils.parseCudaVisibleDevicesAllowlist(parentCudaVisibleDevicesEnv);
        if (allowlist.isEmpty()) {
            String trimmed = parentCudaVisibleDevicesEnv.trim();
            String reason;
            if (trimmed.isEmpty()) {
                reason = "CUDA_VISIBLE_DEVICES is set to empty string";
            } else if (CudaDeviceUtils.isCudaVisibleDevicesDisableSentinel(trimmed)) {
                reason = "CUDA_VISIBLE_DEVICES disables CUDA (value='" + trimmed + "')";
            } else {
                reason = "CUDA_VISIBLE_DEVICES does not allow any devices (value='" + parentCudaVisibleDevicesEnv + "')";
            }
            throw new IllegalArgumentException(
                    "CUDA is not allowed by the parent environment (" + reason + "); worker requested: " + worker
            );
        }
        if (!allowlist.contains(worker)) {
            throw new IllegalArgumentException(
                    "Worker CUDA_VISIBLE_DEVICES must be within the parent allowlist. worker=" + worker + " parent=" + parentCudaVisibleDevicesEnv
            );
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }

        this.closed = true;
        ConnectionHandle handle = this.connectionHandle.getAndSet(null);
        if (handle != null) {
            handle.close();
        }
        try {
            workerExecutor.shutdownNow();
            workerExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long safePid(Process process) {
        try {
            return process.pid();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int safeExitCode(Process process) {
        try {
            return process.exitValue();
        } catch (Exception ignored) {
            return -1;
        }
    }
}
