package com.hotvect.core.annotation.processor.model;

import java.util.List;
import java.util.Objects;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

public record FeatureNode(String name, FeatureKind kind, ExecutableElement method, TypeMirror returnType,
                          List<Param> params) {

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FeatureNode that)) return false;

        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
