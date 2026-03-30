package com.hotvect.core.annotation.processor.model;

import java.util.List;
import java.util.Map;

import javax.lang.model.element.TypeElement;

public record FeatureScanResult(Map<String, FeatureNode> nodesByName,
                                Map<TypeElement, List<FeatureNode>> nodesByClass) {}
