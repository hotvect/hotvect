package com.hotvect.core.annotation.processor.util;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

public final class TypeUtils {
    private TypeUtils() {}

    public static boolean isAssignable(TypeMirror from, TypeMirror to, Types types) {
        if (types.isAssignable(from, to)) {
            return true;
        }
        if (from.getKind().isPrimitive()) {
            TypeMirror boxed = types.boxedClass((javax.lang.model.type.PrimitiveType) from).asType();
            return types.isAssignable(boxed, to);
        }
        if (to.getKind().isPrimitive()) {
            TypeMirror boxed = types.boxedClass((javax.lang.model.type.PrimitiveType) to).asType();
            return types.isAssignable(from, boxed);
        }
        return false;
    }

    public static boolean isSharedContext(TypeMirror paramType, TypeElement sharedContextType, Types types) {
        if (sharedContextType == null || paramType.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declared = (DeclaredType) paramType;
        return types.isAssignable(types.erasure(declared), types.erasure(sharedContextType.asType()));
    }
}
