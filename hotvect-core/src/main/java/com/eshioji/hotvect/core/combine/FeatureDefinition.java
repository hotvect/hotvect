package com.eshioji.hotvect.core.combine;


import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.ValueType;
import com.eshioji.hotvect.api.data.HashedValueType;
import com.eshioji.hotvect.core.hash.HashUtils;
import com.google.common.base.Joiner;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.EnumSet;

import static com.google.common.base.Preconditions.checkArgument;


public class FeatureDefinition<FEATURE extends Enum<FEATURE> & FeatureNamespace> implements Serializable {
    private final ValueType valueType;
    private final EnumSet<FEATURE> components;
    private final FEATURE[] cachedComponents;
    private final String name;
    private final int namespace;

    public FeatureDefinition(FEATURE first) {
        this(EnumSet.of(first));
    }

    public FeatureDefinition(FEATURE first, FEATURE... rest) {
        this(EnumSet.of(first, rest));
    }
    public FeatureDefinition(EnumSet<FEATURE> components) {
        checkArgument(components.size() > 0, "You can't have a feature with no components");

        checkArgument(components.stream().noneMatch(x -> "target".equals(x.name())),
                "You cannot use \"target\" as a feature");

        boolean allCategorical = components.stream().allMatch(x -> x.getValueType() == HashedValueType.CATEGORICAL);

        this.components = components;

        @SuppressWarnings("unchecked")
        FEATURE[] cached = components.toArray((FEATURE[]) Array.newInstance(components.iterator().next().getClass(), components.size()));
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

    public FEATURE[] getComponents() {
        return this.cachedComponents;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FeatureDefinition<?> that = (FeatureDefinition<?>) o;
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
