package com.hotvect.core.annotation.processor.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleAnnotationValueVisitor9;
import javax.lang.model.util.Types;

public final class AnnotationUtils {
    private AnnotationUtils() {}

    public static AnnotationMirror getAnnotationMirror(Element element, TypeElement annotationType, Types types) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (types.isSameType(mirror.getAnnotationType(), annotationType.asType())) {
                return mirror;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getAnnotationValue(AnnotationMirror mirror, String name, Class<T> type, T defaultValue) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror.getElementValues().entrySet()) {
            if (!entry.getKey().getSimpleName().contentEquals(name)) {
                continue;
            }
            AnnotationValue value = entry.getValue();
            return value.accept(new SimpleAnnotationValueVisitor9<T, Void>() {
                @Override
                public T visitString(String s, Void unused) {
                    return type.isInstance(s) ? type.cast(s) : defaultValue;
                }

                @Override
                public T visitType(TypeMirror t, Void unused) {
                    return type.isInstance(t) ? type.cast(t) : defaultValue;
                }

                @Override
                public T visitArray(List<? extends AnnotationValue> vals, Void unused) {
                    if (!List.class.isAssignableFrom(type)) {
                        return defaultValue;
                    }
                    List<Object> out = new ArrayList<>();
                    for (AnnotationValue av : vals) {
                        out.add(av.getValue());
                    }
                    return (T) out;
                }
            }, null);
        }
        return defaultValue;
    }
}
