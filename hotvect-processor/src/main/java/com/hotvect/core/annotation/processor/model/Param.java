package com.hotvect.core.annotation.processor.model;

import javax.lang.model.element.VariableElement;

public record Param(VariableElement element, ParamKind kind, String injectName) {}
