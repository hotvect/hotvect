package com.hotvect.python.direct;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectWorkerProcessTest {
    @Test
    void verifyCudaVisibleDevicesWithinParentAllowlist_allowsCpuWorkersWhenParentSetsAllowlist() {
        assertDoesNotThrow(() -> DirectWorkerProcess.verifyCudaVisibleDevicesWithinParentAllowlist("", "2,3"));
    }

    @Test
    void verifyCudaVisibleDevicesWithinParentAllowlist_rejectsWorkerNotInAllowlist() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> DirectWorkerProcess.verifyCudaVisibleDevicesWithinParentAllowlist("4", "2,3")
        );
        assertTrue(e.getMessage().contains("parent"));
        assertTrue(e.getMessage().contains("2,3"));
    }

    @Test
    void verifyCudaVisibleDevicesWithinParentAllowlist_rejectsWhenParentDisablesCuda() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> DirectWorkerProcess.verifyCudaVisibleDevicesWithinParentAllowlist("0", "-1")
        );
        assertTrue(e.getMessage().contains("-1"));
    }

    @Test
    void restartWorker_clearsStaleHandleAndQueuesRetryTask(@TempDir Path tempDir) throws Exception {
        DirectWorkerManager manager = mock(DirectWorkerManager.class);
        when(manager.hungWorkerTimeout()).thenReturn(Duration.ofSeconds(1));
        when(manager.isClosed()).thenReturn(false);

        RecordingExecutorService executor = new RecordingExecutorService();
        AtomicReference<DirectWorkerProcess> processRef = new AtomicReference<>();
        AtomicInteger restartAttempts = new AtomicInteger();
        DirectWorkerProcess process = new DirectWorkerProcess(
                0,
                new PythonWorkerCommand("/definitely/missing/python", "hotvect.direct_worker.tensorflow_worker"),
                new DirectWorkersConfig(
                        List.of(""),
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        Duration.ZERO,
                        Duration.ZERO,
                        1,
                        QueueFullPolicy.REJECT,
                        1,
                        Duration.ZERO,
                        1024,
                        tempDir
                ),
                manager,
                (command, connectUdsPath, maxFrameBytes, workerIndex, envOverrides) -> {
                    throw new RuntimeException("simulated restart failure");
                },
                executor,
                () -> {
                    restartAttempts.incrementAndGet();
                    try {
                        connectionHandleRef(processRef.get()).set(newConnectionHandle(processRef.get()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
        processRef.set(process);

        setBooleanField(process, "started", true);
        Object existingHandle = newConnectionHandle(process);
        setField(existingHandle, "inFlightRequestId", "req-1");
        connectionHandleRef(process).set(existingHandle);
        java.util.concurrent.TimeoutException failure = new java.util.concurrent.TimeoutException("hung request");

        boolean restartScheduled = invokeRestartWorker(
                process,
                existingHandle,
                failure
        );
        assertTrue(restartScheduled);
        assertNull(connectionHandleRef(process).get());
        assertEquals(1, executor.queuedTaskCount());
        executor.runNext();
        assertEquals(1, restartAttempts.get());
        assertNotNull(connectionHandleRef(process).get());
        verify(manager).failPending("req-1", failure);
        process.close();
    }

    @Test
    void restartUntilHealthy_retriesUntilConnectionEstablished(@TempDir Path tempDir) throws Exception {
        DirectWorkerManager manager = mock(DirectWorkerManager.class);
        when(manager.hungWorkerTimeout()).thenReturn(Duration.ofSeconds(1));
        when(manager.isClosed()).thenReturn(false);

        AtomicInteger startAttempts = new AtomicInteger();
        AtomicReference<DirectWorkerProcess> processRef = new AtomicReference<>();
        DirectWorkerProcess process = new DirectWorkerProcess(
                0,
                new PythonWorkerCommand("/definitely/missing/python", "hotvect.direct_worker.tensorflow_worker"),
                new DirectWorkersConfig(
                        List.of(""),
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        1,
                        QueueFullPolicy.REJECT,
                        1,
                        Duration.ZERO,
                        1024,
                        tempDir
                ),
                manager,
                (command, connectUdsPath, maxFrameBytes, workerIndex, envOverrides) -> {
                    throw new RuntimeException("simulated restart failure");
                },
                new RecordingExecutorService(),
                () -> {
                    int attempt = startAttempts.incrementAndGet();
                    if (attempt == 3) {
                        try {
                            connectionHandleRef(processRef.get()).set(newConnectionHandle(processRef.get()));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
        );
        processRef.set(process);

        setBooleanField(process, "started", true);
        connectionHandleRef(process).set(null);

        invokeRestartUntilHealthy(process);
        assertEquals(3, startAttempts.get());
        assertNotNull(connectionHandleRef(process).get());
        process.close();
    }

    @Test
    void connectionHandleClose_skipsShutdownFrameWhenWriteLockUnavailable(@TempDir Path tempDir) throws Exception {
        DirectWorkerManager manager = mock(DirectWorkerManager.class);
        when(manager.hungWorkerTimeout()).thenReturn(Duration.ofSeconds(1));
        when(manager.isClosed()).thenReturn(false);

        DirectWorkerProcess process = new DirectWorkerProcess(
                0,
                new PythonWorkerCommand("/definitely/missing/python", "hotvect.direct_worker.tensorflow_worker"),
                new DirectWorkersConfig(
                        List.of(""),
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        1,
                        QueueFullPolicy.REJECT,
                        1,
                        Duration.ZERO,
                        1024,
                        tempDir
                ),
                manager,
                (command, connectUdsPath, maxFrameBytes, workerIndex, envOverrides) -> {
                    throw new RuntimeException("unused");
                },
                new RecordingExecutorService()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object handle = newConnectionHandle(process, out);
        java.util.concurrent.locks.ReentrantLock writeLock = writeLock(handle);
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            writeLock.lock();
            try {
                locked.countDown();
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writeLock.unlock();
            }
        });

        assertTrue(locked.await(1, TimeUnit.SECONDS));
        invokeConnectionHandleClose(handle);
        holder.join();
        assertEquals(0, out.size());
        process.close();
    }

    private static boolean invokeRestartWorker(DirectWorkerProcess process, Object handle, Exception failure) throws Exception {
        Method method = DirectWorkerProcess.class.getDeclaredMethod("restartWorker", handle.getClass(), Exception.class);
        method.setAccessible(true);
        return (boolean) method.invoke(process, handle, failure);
    }

    private static void invokeRestartUntilHealthy(DirectWorkerProcess process) throws Exception {
        Method method = DirectWorkerProcess.class.getDeclaredMethod("restartUntilHealthy");
        method.setAccessible(true);
        method.invoke(process);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> connectionHandleRef(DirectWorkerProcess process) throws Exception {
        Field field = DirectWorkerProcess.class.getDeclaredField("connectionHandle");
        field.setAccessible(true);
        return (AtomicReference<Object>) field.get(process);
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object newConnectionHandle(DirectWorkerProcess process) throws Exception {
        return newConnectionHandle(process, null);
    }

    private static Object newConnectionHandle(DirectWorkerProcess process, OutputStream out) throws Exception {
        for (Class<?> nested : DirectWorkerProcess.class.getDeclaredClasses()) {
            if (!nested.getSimpleName().equals("ConnectionHandle")) {
                continue;
            }
            Constructor<?> constructor = nested.getDeclaredConstructor(
                    DirectWorkerProcess.class,
                    Path.class,
                    ServerSocketChannel.class,
                    SocketChannel.class,
                    InputStream.class,
                    OutputStream.class,
                    Process.class,
                    long.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(process, null, null, null, null, out, null, -1L);
        }
        throw new IllegalStateException("ConnectionHandle class not found");
    }

    private static void invokeConnectionHandleClose(Object handle) throws Exception {
        Method method = handle.getClass().getDeclaredMethod("close");
        method.setAccessible(true);
        method.invoke(handle);
    }

    private static java.util.concurrent.locks.ReentrantLock writeLock(Object handle) throws Exception {
        Field field = handle.getClass().getDeclaredField("writeLock");
        field.setAccessible(true);
        return (java.util.concurrent.locks.ReentrantLock) field.get(handle);
    }

    private static final class RecordingExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;
        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            Runnable next = tasks.poll();
            if (next == null) {
                throw new AssertionError("No queued task to run");
            }
            next.run();
        }

        int queuedTaskCount() {
            return tasks.size();
        }
    }
}
