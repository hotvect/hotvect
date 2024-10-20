package com.hotvect.catboost;

import com.google.common.base.Joiner;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.FeatureNamespace;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;

public class CatBoostColumnDescriptionGenerator implements Function<RankingTransformer<?,?>, String> {

    @Override
    public String apply(RankingTransformer<?, ?> input) {
        SortedSet<? extends FeatureNamespace> usedFeatures = input.getUsedFeatures();
        checkArgument(!usedFeatures.isEmpty(), "Used features cannot be empty");
        checkArgument(
                FeatureNamespace.class.isAssignableFrom(usedFeatures.iterator().next().getClass()),
                "To use CatBoost, the features must be of type CatBoostFeatureNamespace. Instead they are: %s",
                usedFeatures.iterator().next().getClass().getName()
                );
        SortedSet<FeatureNamespace> catboostFeatures = (SortedSet<FeatureNamespace>)usedFeatures;

        List<String> toWrite = new ArrayList<>();
        toWrite.add("0\tLabel");

        int count = 1;
        for (FeatureNamespace featureKey : catboostFeatures) {
            // Because of the label using 0, feature index is 1 based
            toWrite.add(String.format("%s\t%s\t%s", count, getCatBoostColumnType((CatBoostFeatureType) featureKey.getFeatureValueType()), featureKey));
            count += 1;
        }

        return Joiner.on('\n').join(toWrite);
    }

    /**
     *
     * @param featureKey
     * @return
     */
    private String getCatBoostColumnType(CatBoostFeatureType featureKey) {

        switch (featureKey) {
            case NUMERICAL:
                return "Num";
            case CATEGORICAL:
                return "Categ";
            case TEXT:
                return "Text";
            case GROUP_ID:
                return "GroupId";
            case EMBEDDING:
                return "NumVector";
            default:
                throw new AssertionError("Catboost column type: " + featureKey + " is not known");
        }
    }
}
