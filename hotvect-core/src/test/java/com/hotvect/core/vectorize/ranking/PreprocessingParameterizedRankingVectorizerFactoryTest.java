//package com.hotvect.core.vectorization.ranking;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.google.common.collect.ImmutableList;
//import com.google.common.collect.ImmutableMap;
//import com.hotvect.api.data.RawValue;
//import com.hotvect.api.data.ranking.RankingRequest;
//import com.hotvect.api.featurestate.FeatureState;
//import com.hotvect.core.transform.ranking.ActionTransformation;
//import com.hotvect.core.transform.ranking.InteractionTransfromation;
//import com.hotvect.api.transformation.ranking.SharedTransformation;
//import org.junit.jupiter.api.Test;
//
//import java.util.EnumMap;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.Optional;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//
//public class PreprocessingParameterizedRankingVectorizerFactoryTest {
//    private final SharedTransformation<Integer> testSharedTransformation = RawValue::singleCategorical;
//    private final ActionTransformation<Short> testActionTransformation = u -> RawValue.singleCategorical(u * 1000);
//    private final InteractionTransfromation<Integer, Short> testInteractionTransformation = (i, u) -> RawValue.singleCategorical(i + (u * 1000));
//
//   @Test
//    void apply() {
//       PreprocessingParameterizedRankingVectorizerFactory<Integer, String, Short, TestFeature> testSubject = new PreprocessingParameterizedRankingVectorizerFactory<>(
//               i -> i + "_preprocessed",
//               TestFeature.class,
//               new RankingTransformationFactory<String, Short, TestFeature>() {
//                   @Override
//                   public EnumMap<TestFeature, InteractionTransfromation<String, Short>> interactionTransformations(Map<String, FeatureState> featureStates) {
//                       EnumMap<TestFeature, InteractionTransfromation<String, Short>> ret = new EnumMap<>(TestFeature.class);
//                       ret.put(TestFeature.test_interaction_feature, (s, u) -> {
//                           assertEquals(s, "0_preprocessed");
//                           return testInteractionTransformation.apply(0, u);
//                       });
//                       return ret;
//                   }
//
//                   @Override
//                   public EnumMap<TestFeature, SharedTransformation<String>> sharedTransformations(Map<String, FeatureState> featureStates) {
//                       EnumMap<TestFeature, SharedTransformation<String>> ret =  new EnumMap<>(TestFeature.class);
//                       ret.put(TestFeature.test_shared_feature, s -> {
//                           assertEquals(s, "0_preprocessed");
//                           return testSharedTransformation.apply(0);
//                       });
//                       return ret;
//                   }
//
//
//                   @Override
//                   public EnumMap<TestFeature, ActionTransformation<Short>> actionTransformation(Map<String, FeatureState> featureStates) {
//                       EnumMap<TestFeature, ActionTransformation<Short>> ret =  new EnumMap<>(TestFeature.class);
//                       ret.put(TestFeature.test_action_feature, testActionTransformation);
//                       return ret;
//                   }
//               }
//       );
//
//       Map<String, Object> testHyperparameterMap = new HashMap<>();
//       testHyperparameterMap.put("hash_bits", 26);
//       testHyperparameterMap.put("features", ImmutableList.of(
//               ImmutableList.of("test_interaction_feature"),
//               ImmutableList.of("test_shared_feature"),
//               ImmutableList.of("test_action_feature")
//       ));
//       Optional<JsonNode> testHyperparameter = Optional.of(new ObjectMapper().valueToTree(testHyperparameterMap));
//
//       var vectorizerWithPreprocessing = testSubject.apply(testHyperparameter, ImmutableMap.of());
//
//       var testRequest = new RankingRequest<>("example1", 0, ImmutableList.of((short)98, (short)99, (short)100));
//
//       var transformedWithPreprocessing = vectorizerWithPreprocessing.apply(testRequest);
//
//
//       var factoryWithoutPreprocessing = new ParameterizedRankingVectorizerFactory<>(
//               TestFeature.class,
//               new RankingTransformationFactory<Integer, Short, TestFeature>() {
//                   @Override
//                   public EnumMap<TestFeature, InteractionTransfromation<Integer, Short>> interactionTransformations(Map<String, FeatureState> featureStates) {
//                       EnumMap<TestFeature, InteractionTransfromation<Integer, Short>> ret = new EnumMap<>(TestFeature.class);
//                       ret.put(TestFeature.test_interaction_feature, testInteractionTransformation);
//                       return ret;
//                   }
//
//                   @Override
//                   public EnumMap<TestFeature, SharedTransformation<Integer>> sharedTransformations(Map<String, FeatureState> featureStates) {
//                       EnumMap<TestFeature, SharedTransformation<Integer>> ret = new EnumMap<>(TestFeature.class);
//                       ret.put(TestFeature.test_shared_feature, testSharedTransformation);
//                       return ret;
//                   }
//
//                   @Override
//                   public EnumMap<TestFeature, ActionTransformation<Short>> actionTransformation(Map<String, FeatureState> featureStates) {
//                       EnumMap<TestFeature, ActionTransformation<Short>> ret = new EnumMap<>(TestFeature.class);
//                       ret.put(TestFeature.test_action_feature, testActionTransformation);
//                       return ret;
//                   }
//               }
//       );
//
//       var vectorizerWithoutPreprocessing = factoryWithoutPreprocessing.apply(testHyperparameter, ImmutableMap.of());
//       var transformedWithoutPreprocessing = vectorizerWithoutPreprocessing.apply(testRequest);
//
//       assertEquals(transformedWithoutPreprocessing, transformedWithPreprocessing);
//
//    }
//}