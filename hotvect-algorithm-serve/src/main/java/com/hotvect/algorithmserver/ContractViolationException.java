package com.hotvect.algorithmserver;

public class ContractViolationException extends RuntimeException {
    private final String details;

    public ContractViolationException(String message, String details) {
        super(message);
        this.details = details;
    }

    public String getDetails() {
        return details;
    }
}
