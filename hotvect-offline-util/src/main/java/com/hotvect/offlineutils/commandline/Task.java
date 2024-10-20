package com.hotvect.offlineutils.commandline;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.utils.VerboseCallable;
import com.hotvect.utils.VerboseRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public abstract class Task extends VerboseCallable<Map<String, Object>> {
    protected final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
    protected final OfflineTaskContext offlineTaskContext;

    protected Task(OfflineTaskContext offlineTaskContext) {
        this.offlineTaskContext = offlineTaskContext;
    }


    protected abstract Map<String, Object> perform() throws Exception;

    @Override
    protected Map<String, Object> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {}", this.getClass().getSimpleName(), offlineTaskContext.getOptions().sourceFiles, offlineTaskContext.getOptions().destinationFile);

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        AtomicLong maxHeapMemoryUsage = new AtomicLong();

        ScheduledExecutorService memoryUsageReporter = exitingScheduledExecutor();
        Runnable reportMemoryUsage = new VerboseRunnable() {
            @Override
            protected void doRun() throws Exception {
                // Best estimate of current RAM usage
                final long totalMemoryUsage = memBean.getHeapMemoryUsage().getUsed() + memBean.getNonHeapMemoryUsage().getUsed();
                maxHeapMemoryUsage.getAndUpdate(previous -> Math.max(totalMemoryUsage, previous));
            }
        };
        memoryUsageReporter.scheduleAtFixedRate(reportMemoryUsage, 5, 2, TimeUnit.SECONDS);
        try {

            Map<String, Object> metadata = perform();
            metadata.put("algorithm_jar", offlineTaskContext.getOptions().algorithmJar);
            metadata.put("task_type", this.getClass().getSimpleName());
            metadata.put("metadata_location", offlineTaskContext.getOptions().metadataLocation.toString());
            if(offlineTaskContext.getOptions().destinationFile != null){
                metadata.put("destination_file", offlineTaskContext.getOptions().destinationFile.toString());
            }
            metadata.put("source_file", offlineTaskContext.getOptions().sourceFiles.toString());
            metadata.put("algorithm_name", offlineTaskContext.getAlgorithmDefinition().getAlgorithmId().getAlgorithmName());
            metadata.put("algorithm_version", offlineTaskContext.getAlgorithmDefinition().getAlgorithmId().getAlgorithmVersion());
            metadata.put("algorithm_definition", offlineTaskContext.getAlgorithmDefinition().toString());
            if (offlineTaskContext.getOptions().parameters != null) {
                metadata.put("parameters", offlineTaskContext.getOptions().parameters);
            }
            metadata.put("max_memory_usage", maxHeapMemoryUsage.get());
            return metadata;
        } finally {
            memoryUsageReporter.shutdown();
        }
    }

    private ScheduledExecutorService exitingScheduledExecutor() {
        return MoreExecutors.getExitingScheduledExecutorService(
                new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder().setNameFormat("memory-reporter").build())
        );
    }



}
