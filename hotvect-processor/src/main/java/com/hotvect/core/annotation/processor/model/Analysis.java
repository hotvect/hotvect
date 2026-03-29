package com.hotvect.core.annotation.processor.model;

import java.util.List;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import javax.lang.model.element.VariableElement;

public record Analysis(Graph<FeatureNode, DefaultEdge> graph, List<TypeMismatch> typeMismatches,
                       List<MissingDependency> missingDependencies, List<DirectionViolation> directionViolations,
                       List<List<FeatureNode>> cycles, Set<FeatureNode> outputs, Set<FeatureNode> reachable,
                       List<FeatureNode> unused, List<FeatureNode> actionToSharedCandidates,
                       List<OutputIssue> outputIssues) {

    public record TypeMismatch(FeatureNode feature, FeatureNode provider, VariableElement param) {}

    public record MissingDependency(FeatureNode feature, String missingName, VariableElement param) {}

    public record DirectionViolation(FeatureNode feature, FeatureNode provider) {}

    public record OutputIssue(String featureName, String message) {}
}
