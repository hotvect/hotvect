package com.hotvect.core.annotation.processor;

import com.hotvect.core.annotation.backend.GeneratedTransformerBackend;
import com.hotvect.core.annotation.backend.Resolution;

/**
 * Test-only {@link GeneratedTransformerBackend} that always throws, used to verify that the processor reports a clean
 * diagnostic (rather than crashing javac) when a third-party backend fails inside {@code resolve}.
 */
public final class ThrowingGeneratedTransformerBackend implements GeneratedTransformerBackend {
    @Override
    public Resolution resolve(String declaredType, String returnTypeName) {
        throw new IllegalStateException("boom");
    }
}
