package com.hotvect.core.annotation.processor.report;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import com.hotvect.core.annotation.processor.ProcessingContext;
import com.hotvect.core.annotation.processor.model.Analysis;
import com.hotvect.core.annotation.processor.model.FeatureNode;
import com.hotvect.core.annotation.processor.model.FeatureScanResult;
import com.hotvect.core.annotation.processor.model.FeatureSpec;
import com.hotvect.core.annotation.processor.model.TransformerSpec;

public final class MarkdownReportWriter {
    private static final String REPORTS_DIR = "META-INF/hotvect/reports";

    private final ProcessingContext context;

    public MarkdownReportWriter(ProcessingContext context) {
        this.context = context;
    }

    public void write(TypeElement specElement, TransformerSpec spec, FeatureScanResult scanResult, Analysis analysis) {
        String specName = spec.name();
        if (specName == null || specName.isBlank() || "GeneratedRankingTransformer".equals(specName)) {
            String base = specElement.getSimpleName().toString();
            if (base.endsWith("Factory") && base.length() > "Factory".length()) {
                specName = base.substring(0, base.length() - "Factory".length());
            } else {
                specName = base;
            }
        }
        String filename = specElement.getQualifiedName().toString().replace('.', '-') + ".md";

        StringBuilder out = new StringBuilder();
        out.append("# Declarative Transformer Report\n\n");
        out.append("## Overview\n");
        out.append("- Spec class: ").append(specElement.getQualifiedName()).append("\n");
        out.append("- Generated name: ").append(specName).append("\n");
        out.append("- Shared type: ").append(spec.sharedType()).append("\n");
        out.append("- Action type: ").append(spec.actionType()).append("\n");
        if (spec.algorithmDefinitionResource() != null && !spec.algorithmDefinitionResource().isBlank()) {
            out.append("- Algorithm definition: ").append(spec.algorithmDefinitionResource()).append("\n");
        }
        out.append("- Backend: ").append(spec.backend()).append("\n");
        out.append("- Feature classes:\n");
        for (TypeElement clazz : scanResult.nodesByClass().keySet()) {
            out.append("  - ").append(clazz.getQualifiedName()).append("\n");
        }
        if (spec.outputFeatures() != null && !spec.outputFeatures().isEmpty()) {
            out.append("- Output features (from algorithm definition):\n");
            for (FeatureSpec feature : spec.outputFeatures()) {
                out.append("  - ").append(feature.name());
                if (feature.type() != null) {
                    out.append(" (type: ").append(feature.type()).append(")");
                }
                out.append("\n");
            }
        }
        out.append("\n");

        Graph<FeatureNode, DefaultEdge> graph = analysis.graph();
        out.append("## Dependency Analysis\n");
        out.append("- Nodes: ").append(graph.vertexSet().size()).append("\n");
        out.append("- Edges: ").append(graph.edgeSet().size()).append("\n");
        if (!analysis.cycles().isEmpty()) {
            out.append("- Cycles:\n");
            for (List<FeatureNode> cycle : analysis.cycles()) {
                out.append("  - ").append(joinCycle(cycle)).append("\n");
            }
        }
        out.append("\n");

        out.append("## Feature Dependencies\n");
        writeFeatureDependencies(out, analysis);
        out.append("\n");

        out.append("## Discovered Transformations\n");
        for (Map.Entry<TypeElement, List<FeatureNode>> entry : scanResult.nodesByClass().entrySet()) {
            out.append("- ").append(entry.getKey().getQualifiedName()).append("\n");
            for (FeatureNode node : entry.getValue()) {
                out.append("  - ").append(node.kind()).append(" ").append(node.name())
                        .append(" : ").append(node.returnType()).append("\n");
            }
        }
        out.append("\n");

        out.append("## Type Checks\n");
        if (analysis.typeMismatches().isEmpty() && analysis.missingDependencies().isEmpty()) {
            out.append("- All injected types resolved successfully.\n");
        } else {
            for (Analysis.TypeMismatch mismatch : analysis.typeMismatches()) {
                out.append("- Type mismatch: ").append(mismatch.feature().name())
                        .append(" expects ").append(mismatch.param().asType())
                        .append(" but provider ").append(mismatch.provider().name())
                        .append(" returns ").append(mismatch.provider().returnType()).append("\n");
            }
            for (Analysis.MissingDependency missing : analysis.missingDependencies()) {
                out.append("- Missing dependency: ").append(missing.feature().name())
                        .append(" injects '").append(missing.missingName()).append("'\n");
            }
        }
        out.append("\n");

        out.append("## Output Feature Validation\n");
        if (analysis.outputIssues().isEmpty()) {
            out.append("- All output features resolved successfully.\n");
        } else {
            for (Analysis.OutputIssue issue : analysis.outputIssues()) {
                out.append("- ").append(issue.featureName()).append(": ").append(issue.message()).append("\n");
            }
        }
        out.append("\n");

        out.append("## Dependency Direction\n");
        if (analysis.directionViolations().isEmpty()) {
            out.append("- None\n");
        } else {
            for (Analysis.DirectionViolation violation : analysis.directionViolations()) {
                out.append("- Shared feature ").append(violation.feature().name())
                        .append(" depends on action feature ").append(violation.provider().name()).append("\n");
            }
        }
        out.append("\n");

        out.append("## Unused Transformations\n");
        if (analysis.unused().isEmpty()) {
            out.append("- None\n");
        } else {
            out.append("- Unused features are excluded from generated code.\n");
            for (FeatureNode node : analysis.unused()) {
                out.append("- ").append(node.kind()).append(" ").append(node.name()).append("\n");
            }
        }
        out.append("\n");

        out.append("## Action -> Shared Candidates\n");
        if (analysis.actionToSharedCandidates().isEmpty()) {
            out.append("- None\n");
        } else {
            for (FeatureNode node : analysis.actionToSharedCandidates()) {
                out.append("- ").append(node.name()).append("\n");
            }
        }
        out.append("\n");

        out.append("## Notes\n");
        if (spec.outputFeatures() == null || spec.outputFeatures().isEmpty()) {
            out.append("- Output feature list missing from algorithm definition; code generation will fail.\n");
        } else {
            out.append("- Output feature list sourced from algorithm definition.\n");
            out.append("- Generated code includes only outputs and their dependencies.\n");
        }

        writeFile(specElement, filename, out.toString());
    }

    private void writeFile(TypeElement specElement, String filename, String content) {
        Filer filer = context.filer();
        try {
            FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", REPORTS_DIR + "/" + filename, specElement);
            try (Writer writer = file.openWriter()) {
                writer.write(content);
            }
            context.messager().printMessage(Diagnostic.Kind.NOTE,
                    "Wrote declarative transformer report to " + file.toUri());
        } catch (FilerException ex) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write report (file conflict): " + ex.getMessage(), specElement);
        } catch (IOException ex) {
            context.messager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write report: " + ex.getMessage(), specElement);
        }
    }

    private String joinCycle(List<FeatureNode> cycle) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cycle.size(); i++) {
            if (i > 0) {
                builder.append(" -> ");
            }
            builder.append(cycle.get(i).name());
        }
        return builder.toString();
    }

    private void writeFeatureDependencies(StringBuilder out, Analysis analysis) {
        Graph<FeatureNode, DefaultEdge> graph = analysis.graph();
        List<FeatureNode> nodes = new ArrayList<>(graph.vertexSet());
        nodes.sort(Comparator.comparing(FeatureNode::kind).thenComparing(FeatureNode::name));

        Map<FeatureNode, List<FeatureNode>> depsByNode = new LinkedHashMap<>();
        for (FeatureNode node : nodes) {
            List<FeatureNode> deps = new ArrayList<>();
            for (DefaultEdge edge : graph.outgoingEdgesOf(node)) {
                deps.add(graph.getEdgeTarget(edge));
            }
            deps.sort(Comparator.comparing(FeatureNode::name));
            depsByNode.put(node, deps);
        }

        Map<FeatureNode, List<Analysis.MissingDependency>> missingByNode = new HashMap<>();
        for (Analysis.MissingDependency missing : analysis.missingDependencies()) {
            missingByNode.computeIfAbsent(missing.feature(), ignored -> new ArrayList<>()).add(missing);
        }

        Map<FeatureNode, Map<String, Analysis.TypeMismatch>> mismatchByNode = new HashMap<>();
        for (Analysis.TypeMismatch mismatch : analysis.typeMismatches()) {
            mismatchByNode
                    .computeIfAbsent(mismatch.feature(), ignored -> new HashMap<>())
                    .put(mismatch.provider().name(), mismatch);
        }

        Map<FeatureNode, Set<String>> directionViolations = new HashMap<>();
        for (Analysis.DirectionViolation violation : analysis.directionViolations()) {
            directionViolations
                    .computeIfAbsent(violation.feature(), ignored -> new HashSet<>())
                    .add(violation.provider().name());
        }

        for (FeatureNode node : nodes) {
            out.append("- ").append(node.kind()).append(" ").append(node.name()).append("\n");
            List<FeatureNode> deps = depsByNode.getOrDefault(node, List.of());
            List<Analysis.MissingDependency> missing = missingByNode.getOrDefault(node, List.of());

            if (deps.isEmpty() && missing.isEmpty()) {
                out.append("  - None\n");
                continue;
            }

            Map<String, Analysis.TypeMismatch> mismatches = mismatchByNode.getOrDefault(node, Map.of());
            Set<String> direction = directionViolations.getOrDefault(node, Set.of());

            for (FeatureNode dep : deps) {
                StringBuilder line = new StringBuilder("  - ");
                line.append(dep.name());
                Analysis.TypeMismatch mismatch = mismatches.get(dep.name());
                if (mismatch != null) {
                    line.append(" (ERROR: type mismatch, expected ")
                            .append(mismatch.param().asType())
                            .append(", got ")
                            .append(mismatch.provider().returnType())
                            .append(")");
                }
                if (direction.contains(dep.name())) {
                    line.append(" (ERROR: shared feature depends on action feature)");
                }
                out.append(line).append("\n");
            }

            for (Analysis.MissingDependency miss : missing) {
                out.append("  - ").append(miss.missingName()).append(" (ERROR: missing)\n");
            }
        }
    }
}
