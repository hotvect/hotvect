package com.hotvect.onlineutils.hotdeploy.util;

public class CatBoostModelClosedException extends RuntimeException {
    public CatBoostModelClosedException(String message) {
        super(message);
    }

    public CatBoostModelClosedException(String message, Throwable cause) {
        super(message, cause);
    }

    public CatBoostModelClosedException(Throwable cause) {
        super(cause);
    }
}
