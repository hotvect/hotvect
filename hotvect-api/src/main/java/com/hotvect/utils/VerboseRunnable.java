package com.hotvect.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class VerboseRunnable implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public final void run() {
        try {
            doRun();
        } catch (Throwable t) {
            logger.error("Error while running:" + this, t);
            throw new RuntimeException(t);
        }
    }

    protected abstract void doRun() throws Exception;
}
