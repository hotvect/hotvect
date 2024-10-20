package com.hotvect.onlineutils.concurrency;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.*;

public class CommonPool {
    private static final Logger logger = LoggerFactory.getLogger(CommonPool.class);
    private static final ExecutorService COMMON_FIXED_BOUNDED_POOL;
    private static final ForkJoinPool COMMON_FORK_JOIN_POOL;

    static {
        //noinspection removal
        COMMON_FIXED_BOUNDED_POOL = AccessController.doPrivileged((PrivilegedAction<ExecutorService>) () -> {
            int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            int queueSize = numThreads * 16;
            ThreadFactory threadFactory = new ThreadFactoryBuilder()
                    .setNameFormat("hotvect-elastic-pool-%s")
                    .setUncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception in thread:" + t.getName(), e))
                    .build();


            ThreadPoolExecutor exec = new ThreadPoolExecutor(
                    numThreads,
                    numThreads,
                    1,
                    TimeUnit.DAYS,
                    new ArrayBlockingQueue<>(queueSize),
                    threadFactory,
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
            return MoreExecutors.getExitingExecutorService(exec);
        });
        //noinspection removal
        COMMON_FORK_JOIN_POOL = AccessController.doPrivileged((PrivilegedAction<ForkJoinPool>) () -> {
            int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

            ForkJoinPool exec = new ForkJoinPool(
                    numThreads,
                    ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                    (t, e) -> logger.error("Uncaught exception in thread:" + t.getName(), e),
                    false
            );
            return exec;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(){
            @Override
            public void run() {
                try {
                    // We'd like to log progress and failures that may arise in the
                    // following code, but unfortunately the behavior of logging
                    // is undefined in shutdown hooks.
                    // This is because the logging code installs a shutdown hook of its
                    // own. See Cleaner class inside {@link LogManager}.
                    COMMON_FORK_JOIN_POOL.shutdown();
                    COMMON_FORK_JOIN_POOL.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // We're shutting down anyway, so just ignore.
                }
            }
        });
    }

    private CommonPool() {
    }

    public static ExecutorService commonFixedBoundedPool() {
        return COMMON_FIXED_BOUNDED_POOL;
    }

    public static ForkJoinPool commonForkJoinPool(){
        return COMMON_FORK_JOIN_POOL;
    }



}
