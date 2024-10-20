package com.hotvect.api.transformation.memoization;

public class NamespaceNotFoundException extends RuntimeException {
    public NamespaceNotFoundException(String msg) {
        super(msg);
    }

    public NamespaceNotFoundException(Throwable cause) {
        super(cause);
    }

    public NamespaceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
