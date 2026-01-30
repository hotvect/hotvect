package com.hotvect.core.transform;

public class NamespaceNotFoundException extends WrongTransformationDefinitionException {
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
