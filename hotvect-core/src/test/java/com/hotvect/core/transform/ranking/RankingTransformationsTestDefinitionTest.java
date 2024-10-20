//package com.hotvect.core.transform.ranking;
//
//import com.google.common.collect.ImmutableMap;
//import com.google.common.collect.ImmutableSet;
//import com.hotvect.core.v6.dsl.RankingTransformationDefinition;
//import org.junit.jupiter.api.Test;
//
//
//class RankingTransformationsTestDefinitionTest {
//
//    @Test
//    void test() {
//
//        RankingTransformationDefinition
//                .define()
//                .withInputs(TestShared.class, TestAction.class)
//                .withActionTransformations(ImmutableMap.of())
//                .withInteractionTransformations(ImmutableMap.of())
//                .withSharedTransformations(ImmutableMap.of())
//                .withFeatures(ImmutableSet.of())
//                .build();
//
//
////        RankingTransformationFactory<TestShared, TestAction, TestFeature> factory =
////
////
////        RankingTransformationDefinition<Map<String, Double>, String> meh  = RankingTransformations.<Map<String, Double>, String>transformationOf(Map.class, String.class);
//    }
//}