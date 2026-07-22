package com.hotvect.algorithmserver;

import java.io.IOException;

public final class RequestBodyTooLargeException extends IOException {
    private final String details;

    public RequestBodyTooLargeException(long maxBytes) {
        this(maxBytes, null);
    }

    public RequestBodyTooLargeException(long maxBytes, Long maxRequestMiBOrNull) {
        super("Request body exceeds max-request-mib");
        this.details = maxRequestMiBOrNull == null
                ? "max_bytes=" + maxBytes
                : "max_mib=" + maxRequestMiBOrNull + ", max_bytes=" + maxBytes;
    }

    public String getDetails() {
        return details;
    }
}
