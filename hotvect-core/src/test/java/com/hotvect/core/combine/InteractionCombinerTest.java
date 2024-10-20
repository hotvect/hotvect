//package com.hotvect.core.combine;
//
//import com.google.common.collect.ImmutableSet;
//import com.hotvect.api.data.DataRecord;
//import com.hotvect.api.data.HashedValue;
//import com.hotvect.api.data.SparseVector;
//import com.hotvect.core.TestFeatureNamespace;
//import org.junit.jupiter.api.Test;
//
//import java.util.EnumSet;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.Set;
//
//import static com.hotvect.core.TestFeatureNamespace.*;
//import static org.junit.jupiter.api.Assertions.assertEquals;
//
//class InteractionCombinerTest {
//
//    @Test
//    void apply() {
//        Set<FeatureDefinition<TestFeatureNamespace>> fds = ImmutableSet.of(
//                new FeatureDefinition<>(EnumSet.of(single_numerical_1)),
//                new FeatureDefinition<>(EnumSet.of(single_categorical_1)),
//                new FeatureDefinition<>(EnumSet.of(categorical_id_to_numericals_1)),
//                new FeatureDefinition<>(EnumSet.of(categoricals_1)),
//                new FeatureDefinition<>(EnumSet.of(categoricals_1, single_categorical_1))
//        );
//        InteractionCombiner<TestFeatureNamespace> subject = new InteractionCombiner<>(32, fds);
//
//
//        DataRecord<TestFeatureNamespace, HashedValue> testData = new DataRecord<>(TestFeatureNamespace.class);
//        testData.put(single_numerical_1, HashedValue.singleNumerical(2.0));
//        testData.put(single_categorical_1, HashedValue.singleCategorical(1));
//        testData.put(categorical_id_to_numericals_1, HashedValue.numericals(new int[]{1, 2, 3}, new double[]{6.0, 7.0, 8.0}));
//        testData.put(categoricals_1, HashedValue.categoricals(new int[]{1, 2, 3}));
//
//        SparseVector actual = subject.apply(testData);
//        assertEquals(8, actual.getCategoricalIndices().length);
//        assertEquals(4, actual.getNumericalIndices().length);
//
//        Map<Integer, Double> expected = new HashMap<>();
//        expected.put(0, 1.0);
//        expected.put(-126422729, 8.0);
//        expected.put(-1091354287, 1.0);
//        expected.put(1738566435, 1.0);
//        expected.put(1459019566, 1.0);
//        expected.put(-1975401671, 7.0);
//        expected.put(316108942, 2.0);
//        expected.put(362240301, 1.0);
//        expected.put(147495152, 1.0);
//        expected.put(-584055558, 1.0);
//        expected.put(-1361829256, 1.0);
//        expected.put(822261868, 6.0);
//
//        Map<Integer, Double> actualout = new HashMap<>();
//        for (int i = 0; i < actual.getNumericalIndices().length; i++) {
//            actualout.put(actual.getNumericalIndices()[i], actual.getNumericalValues()[i]);
//        }
//        for (int i = 0; i < actual.getCategoricalIndices().length; i++) {
//            actualout.put(actual.getCategoricalIndices()[i], 1.0);
//        }
//        assertEquals(expected, actualout);
//    }
//}