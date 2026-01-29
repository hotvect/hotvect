package com.hotvect.onlineutils.concurrency;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

public class CommonPool {
    private static final Logger logger = LoggerFactory.getLogger(CommonPool.class);
    private static final ExecutorService COMMON_FIXED_BOUNDED_POOL;
    private static final ForkJoinPool COMMON_FORK_JOIN_POOL;
    private static final ForkJoinPool FORK_JOIN_POOL_WITH_SLACK;

    static {
        int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int queueSize = numThreads * 16;
        ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("hotvect-elastic-pool-%s")
                .setUncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception in thread:{}", t.getName(), e))
                .build();
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                numThreads, numThreads,
                1, TimeUnit.DAYS,
                new ArrayBlockingQueue<>(queueSize),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        COMMON_FIXED_BOUNDED_POOL = MoreExecutors.getExitingExecutorService(exec);

        numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        COMMON_FORK_JOIN_POOL = new ForkJoinPool(
                numThreads,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t1, e1) -> logger.error("Uncaught exception in thread:{}", t1.getName(), e1),
                false
        );

        FORK_JOIN_POOL_WITH_SLACK = new ForkJoinPool(
                (int)(numThreads * 1.2),
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> logger.error("Uncaught exception in thread:{}", t.getName(), e),
                false
        );


        Runtime.getRuntime().addShutdownHook(new Thread(){
            @Override
            public void run() {
                try {
                    COMMON_FORK_JOIN_POOL.shutdown();
                    COMMON_FORK_JOIN_POOL.awaitTermination(30, TimeUnit.SECONDS);
                    FORK_JOIN_POOL_WITH_SLACK.shutdown();
                    FORK_JOIN_POOL_WITH_SLACK.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
    }

    private CommonPool() {
    }

    /**
     * Returns a common fixed bounded thread pool.
     * @return
     */
    public static ExecutorService commonFixedBoundedPool() {
        return COMMON_FIXED_BOUNDED_POOL;
    }

    /**
     * Returns a common fork join pool meant fully for CPU bound tasks
     * @return
     */
    public static ForkJoinPool commonForkJoinPool(){
        return COMMON_FORK_JOIN_POOL;
    }

    /**
     * Returns a fork join pool with slightly more threads, so that some IO blocks are tolerated.
     */
    public static ForkJoinPool forkJoinPoolWithSlack() {
        return FORK_JOIN_POOL_WITH_SLACK;
    }
}