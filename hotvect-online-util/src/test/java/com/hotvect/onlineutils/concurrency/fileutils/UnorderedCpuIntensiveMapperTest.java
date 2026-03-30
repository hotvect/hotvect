package com.hotvect.onlineutils.concurrency.fileutils;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnorderedCpuIntensiveMapperTest {

    @Test
    void processingDoneIsSetOnlyAfterMapperTermination() throws Exception {
        UnorderedMultiFileReader.ReadState<String> readState =
                new UnorderedMultiFileReader.ReadState<>(new LinkedBlockingQueue<>(16));
        UnorderedFileMapper.MultiFileState<String, ByteBuffer> state =
                new UnorderedFileMapper.MultiFileState<>(readState, 16);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Function<String, List<ByteBuffer>> mapperFn = value -> {
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "Timed out waiting to release mapper");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return List.of(ByteBuffer.wrap((value + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
        };

        Timer timer = Timer.builder("test.mapper").register(new SimpleMeterRegistry());
        UnorderedCpuIntensiveMapper<String, ByteBuffer> mapper =
                new UnorderedCpuIntensiveMapper<>(state, timer, mapperFn, 2, 1);

        readState.getReadQueue().put("1");
        readState.setReadDone();
        mapper.start();

        assertTrue(started.await(5, TimeUnit.SECONDS), "A mapper thread should start processing the first batch");
        Thread.sleep(200);
        assertFalse(
                state.isProcessingDone(),
                "processingDone must not be set while another worker can still enqueue outputs"
        );

        release.countDown();
        mapper.awaitTermination();

        assertTrue(state.isProcessingDone());
        assertEquals(1, state.getWriteQueue().size());
    }
}

