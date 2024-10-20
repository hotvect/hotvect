package com.hotvect.core.combine;


import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.HashedValueType;
import com.hotvect.api.data.ValueType;
import com.hotvect.core.hash.HashUtils;

import java.io.Serializable;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;


public class FeatureDefinition implements Serializable {
    private final ValueType valueType;
    private final Set<FeatureNamespace> components;
    private final FeatureNamespace[] cachedComponents;
    private final String name;
    private final int namespace;

    public FeatureDefinition(FeatureNamespace first) {
        this(asIdentitySet(List.of(first)));
    }

    private static Set<FeatureNamespace> asIdentitySet(Collection<FeatureNamespace> components) {
        Set<FeatureNamespace> set = Sets.newIdentityHashSet();
        set.addAll(components);
        return set;
    }

    public FeatureDefinition(FeatureNamespace first, FeatureNamespace... rest) {
        this(asIdentitySet(Lists.asList(first, rest)));
    }
    public FeatureDefinition(Set<FeatureNamespace> components) {
        checkArgument(!components.isEmpty(), "You can't have a feature with no components");

        boolean allCategorical = components.stream().noneMatch(x -> x.getFeatureValueType().hasNumericValues());

        this.components = components;

        List<FeatureNamespace> alphabetical = new ArrayList<>(components);
        alphabetical.sort(FeatureNamespace.alphabetical());

        FeatureNamespace[] cached = alphabetical.toArray(new FeatureNamespace[0]);
        this.cachedComponents = cached;

        this.name = Joiner.on("^").join(components);
        this.namespace = HashUtils.hashUnencodedChars(name);

        if (allCategorical) {
            valueType = HashedValueType.CATEGORICAL;
        } else {
            checkArgument(components.size() == 1, "You can't create an interactive feature with numerical components");
            valueType = HashedValueType.NUMERICAL;
        }
    }

    public FeatureNamespace[] getComponents() {
        return this.cachedComponents;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FeatureDefinition that = (FeatureDefinition) o;
        return Arrays.equals(this.cachedComponents, that.cachedComponents);
    }

    @Override
    public int hashCode() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public int getFeatureNamespace() {
        return namespace;
    }

    public ValueType getValueType() {
        return valueType;
    }

    @Override
    public String toString() {
        return "FeatureDefinition{" +
                "valueType=" + valueType +
                ", components=" + components +
                ", name='" + name +
                '}';
    }
}
