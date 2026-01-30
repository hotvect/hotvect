package com.hotvect.catboost;

import com.google.common.base.Joiner;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.Namespace;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

public class CatBoostColumnDescriptionGenerator implements Function<RankingTransformer<?,?>, String> {

    @Override
    public String apply(RankingTransformer<?, ?> input) {
        SortedSet<? extends Namespace> usedFeatures = input.getUsedFeatures();
        checkArgument(!usedFeatures.isEmpty(), "Used features cannot be empty");
        checkArgument(
                usedFeatures.stream().map(Namespace::getFeatureValueType).allMatch(x -> x instanceof CatBoostFeatureType),
                "To use CatBoost, the features types must be of type CatBoostFeatureType. Instead they contain: %s",
                usedFeatures.stream().map(Namespace::getFeatureValueType).filter(x -> !(x instanceof CatBoostFeatureType)).collect(Collectors.toSet())
                );
        SortedSet<Namespace> catboostFeatures = (SortedSet<Namespace>)usedFeatures;

        List<String> toWrite = new ArrayList<>();
        toWrite.add("0\tLabel");

        int count = 1;
        for (Namespace featureKey : catboostFeatures) {
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

        return switch (featureKey) {
            case NUMERICAL -> "Num";
            case CATEGORICAL -> "Categ";
            case TEXT -> "Text";
            case GROUP_ID -> "GroupId";
            case EMBEDDING -> "NumVector";
        };
    }
}
