package com.hotvect.api.transformation.memoization;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.transformation.RankingFeatureComputationDependency;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Beta
public class TransformationMetadata {
    private final Namespace columnName;
    private final List<Namespace> columnNameComponents;
    private final List<String> namespaceComponentClasses;
    private final RankingFeatureComputationDependency computationDependency;
    private final String boundMethodName;
    private final Type returnType;
    private final boolean enabledAsFeature;
    private final boolean cacheEnabled;
    private final boolean canBeFeature;

    public TransformationMetadata(Namespace columnName, List<Namespace> columnNameComponents, RankingFeatureComputationDependency computationDependency, String boundMethodName, Type returnType, boolean enabledAsFeature, boolean cacheEnabled) {
        this.columnName = columnName;
        this.columnNameComponents = ImmutableList.copyOf(columnNameComponents);
        this.namespaceComponentClasses = this.columnNameComponents.stream().map(x->((Enum<?>)x).getDeclaringClass()).map(Class::getName).collect(Collectors.toList());
        this.computationDependency = computationDependency;
        this.boundMethodName = boundMethodName;
        this.returnType = returnType;
        this.enabledAsFeature = enabledAsFeature;
        this.cacheEnabled = cacheEnabled;
        this.canBeFeature = columnName instanceof FeatureNamespace;
    }

    public Namespace getNamespace() {
        return columnName;
    }

    public boolean isCanBeFeature() {
        return canBeFeature;
    }

    public List<Namespace> getNamespaceComponents() {
        return columnNameComponents;
    }

    public List<String> getNamespaceComponentClasses() {
        return namespaceComponentClasses;
    }

    public RankingFeatureComputationDependency getComputationDependency() {
        return computationDependency;
    }

    public String getBoundMethodName() {
        return boundMethodName;
    }

    public Type getReturnType() {
        return returnType;
    }

    public boolean isEnabledAsFeature() {
        return enabledAsFeature;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransformationMetadata that = (TransformationMetadata) o;
        return enabledAsFeature == that.enabledAsFeature && cacheEnabled == that.cacheEnabled && canBeFeature == that.canBeFeature && Objects.equals(columnName, that.columnName) && Objects.equals(columnNameComponents, that.columnNameComponents) && Objects.equals(namespaceComponentClasses, that.namespaceComponentClasses) && computationDependency == that.computationDependency && Objects.equals(boundMethodName, that.boundMethodName) && Objects.equals(returnType, that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnName, columnNameComponents, namespaceComponentClasses, computationDependency, boundMethodName, returnType, enabledAsFeature, cacheEnabled, canBeFeature);
    }

    @Override
    public String toString() {
        return "TransformationMetadata{" +
                "namespace=" + columnName +
                ", namespaceComponents=" + columnNameComponents +
                ", namespaceComponentClasses=" + namespaceComponentClasses +
                ", computationDependency=" + computationDependency +
                ", boundMethodName='" + boundMethodName + '\'' +
                ", returnType=" + returnType +
                ", enabledAsFeature=" + enabledAsFeature +
                ", cacheEnabled=" + cacheEnabled +
                ", canBeFeature=" + canBeFeature +
                '}';
    }
}
