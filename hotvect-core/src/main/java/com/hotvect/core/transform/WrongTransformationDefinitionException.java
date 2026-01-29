package com.hotvect.core.transform;

public class WrongTransformationDefinitionException extends RuntimeException {
    public WrongTransformationDefinitionException(String message) {
        super(message);
    }

    public WrongTransformationDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }

    public WrongTransformationDefinitionException(Throwable cause) {
        super(cause);
    }
}
