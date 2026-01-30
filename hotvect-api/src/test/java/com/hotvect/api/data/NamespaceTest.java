package com.hotvect.api.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the Namespace interface's getReturnTypeHint method.
 */
public class NamespaceTest {

    /**
     * Test getReturnTypeHint when the enum constant does not have the @ReturnType annotation.
     */
    @Test
    public void testGetReturnTypeHint_withoutReturnTypeAnnotation() throws NoSuchFieldException {
        Namespace ns = TestNamespace.FEATURE_WITHOUT_ANNOTATION;
        Class<?> returnType = ns.getReturnTypeHint();
        assertNull(returnType);
    }

    /**
     * An enum implementing Namespace with and without @ReturnType annotations.
     */
    public enum TestNamespace implements Namespace {
        FEATURE_WITH_ANNOTATION,

        FEATURE_WITHOUT_ANNOTATION;
    }

    /**
     * A non-enum implementation of Namespace to test error handling.
     */
    public static class NonEnumNamespace implements Namespace {
        // No additional implementation needed
    }
}