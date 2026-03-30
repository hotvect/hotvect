package com.hotvect.core.annotation.processor.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.hotvect.core.annotation.processor.model.Analysis;
import com.hotvect.core.annotation.processor.model.FeatureKind;
import com.hotvect.core.annotation.processor.model.FeatureNode;
import com.hotvect.core.annotation.processor.model.Param;
import com.hotvect.core.annotation.processor.model.ParamKind;
import com.hotvect.core.annotation.processor.util.TypeUtils;

import javax.lang.model.util.Types;

public final class GraphAnalyzer {
    private final Types types;

    public GraphAnalyzer(Types types) {
        this.types = types;
    }

    public Analysis analyze(Map<String, FeatureNode> nodesByName, List<String> outputFeatureNames) {
        Graph<FeatureNode, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        for (FeatureNode node : nodesByName.values()) graph.addVertex(node);

        List<Analysis.TypeMismatch> typeMismatches = new ArrayList<>();
        List<Analysis.MissingDependency> missingDependencies = new ArrayList<>();
        List<Analysis.DirectionViolation> directionViolations = new ArrayList<>();

        for (FeatureNode node : nodesByName.values()) {
            for (Param param : node.params()) {
                if (param.kind() != ParamKind.INJECTED) continue;

                String depName = param.injectName();
                FeatureNode provider = nodesByName.get(depName);
                if (provider == null) {
                    missingDependencies.add(new Analysis.MissingDependency(node, depName, param.element()));
                    continue;
                }

                graph.addEdge(node, provider);

                if (node.kind() == FeatureKind.SHARED && provider.kind() == FeatureKind.ACTION) {
                    directionViolations.add(new Analysis.DirectionViolation(node, provider));
                }

                if (!TypeUtils.isAssignable(provider.returnType(), param.element().asType(), types)) {
                    typeMismatches.add(new Analysis.TypeMismatch(node, provider, param.element()));
                }
            }
        }

        List<List<FeatureNode>> cycles = new JohnsonSimpleCycles<>(graph).findSimpleCycles();

        Set<FeatureNode> outputs = new LinkedHashSet<>();
        List<Analysis.OutputIssue> outputIssues = new ArrayList<>();

        if (outputFeatureNames.isEmpty()) {
            outputIssues.add(new Analysis.OutputIssue(null, "No output features specified"));
        }

        for (String name : outputFeatureNames) {
            FeatureNode node = nodesByName.get(name);
            if (node == null) {
                outputIssues.add(new Analysis.OutputIssue(name, "Output feature not found"));
                continue;
            }
            if (node.kind() != FeatureKind.ACTION) {
                outputIssues.add(new Analysis.OutputIssue(name, "Output feature must be a @Feature method"));
                continue;
            }
            outputs.add(node);
        }

        Set<FeatureNode> reachable = computeReachable(outputs, graph);
        List<FeatureNode> unused = new ArrayList<>();
        for (FeatureNode node : nodesByName.values()) {
            if (!reachable.contains(node)) {
                unused.add(node);
            }
        }

        List<FeatureNode> actionToSharedCandidates = new ArrayList<>();
        for (FeatureNode node : nodesByName.values()) {
            if (node.kind() == FeatureKind.SHARED) continue;
            if (hasActionParam(node)) continue;
            if (dependsOnAction(node, graph)) continue;

            actionToSharedCandidates.add(node);
        }

        return new Analysis(graph, typeMismatches, missingDependencies, directionViolations, cycles, outputs,
                reachable, unused, actionToSharedCandidates, outputIssues);
    }

    private boolean hasActionParam(FeatureNode node) {
        for (Param param : node.params()) {
            if (param.kind() == ParamKind.ACTION) return true;
        }

        return false;
    }

    private boolean dependsOnAction(FeatureNode node, Graph<FeatureNode, DefaultEdge> graph) {
        Set<FeatureNode> visited = new HashSet<>();
        Deque<FeatureNode> stack = new ArrayDeque<>();
        for (DefaultEdge edge : graph.outgoingEdgesOf(node)) {
            stack.add(graph.getEdgeTarget(edge));
        }

        while (!stack.isEmpty()) {
            FeatureNode dep = stack.pop();
            if (!visited.add(dep)) {
                continue;
            }
            if (dep.kind() == FeatureKind.ACTION) {
                return true;
            }
            for (DefaultEdge edge : graph.outgoingEdgesOf(dep)) {
                stack.add(graph.getEdgeTarget(edge));
            }
        }
        return false;
    }

    private Set<FeatureNode> computeReachable(Set<FeatureNode> outputs, Graph<FeatureNode, DefaultEdge> graph) {
        Set<FeatureNode> reachable = new HashSet<>();
        Deque<FeatureNode> stack = new ArrayDeque<>(outputs);
        while (!stack.isEmpty()) {
            FeatureNode node = stack.pop();
            if (!reachable.add(node)) {
                continue;
            }
            for (DefaultEdge edge : graph.outgoingEdgesOf(node)) {
                stack.add(graph.getEdgeTarget(edge));
            }
        }
        return reachable;
    }
}
