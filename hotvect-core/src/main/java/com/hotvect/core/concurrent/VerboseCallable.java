package com.hotvect.core.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public abstract class VerboseCallable<V> implements Callable<V> {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public final V call() throws Exception {
        try {
            return doCall();
        } catch (Throwable t) {
            logger.error("Error while running:" + this.toString(), t);
            throw new RuntimeException(t);
        }
    }

    protected abstract V doCall() throws Exception;
}
