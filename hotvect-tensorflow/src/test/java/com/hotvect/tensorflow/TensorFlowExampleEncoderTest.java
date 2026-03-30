package com.hotvect.tensorflow;

import com.hotvect.api.data.Namespace;
import org.junit.jupiter.api.Test;
import org.tensorflow.proto.Feature;
import org.tensorflow.proto.Features;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TensorFlowExampleEncoderTest {

    @Test
    void categoricalSequenceListUsesDefaultForNullElements() {
        Features.Builder builder = Features.newBuilder();

        TensorFlowExampleEncoder.putTransformerFeature(
                builder,
                namespace("categorical_sequence", TensorFlowFeatureType.categoricalSequence(3)),
                Arrays.asList(1, null, 3)
        );

        Feature feature = builder.getFeatureMap().get("categorical_sequence");
        assertEquals(List.of(1L, 0L, 3L), feature.getInt64List().getValueList());
    }

    @Test
    void numericalSequenceBoxedArrayUsesDefaultForNullElements() {
        Features.Builder builder = Features.newBuilder();

        TensorFlowExampleEncoder.putTransformerFeature(
                builder,
                namespace("numerical_sequence", TensorFlowFeatureType.numericalSequence(3)),
                new Double[]{1.5, null, 3.25}
        );

        Feature feature = builder.getFeatureMap().get("numerical_sequence");
        assertEquals(List.of(1.5f, 0.0f, 3.25f), feature.getFloatList().getValueList());
    }

    @Test
    void nullSequenceValueFillsVectorWithDefaults() {
        Features.Builder builder = Features.newBuilder();

        TensorFlowExampleEncoder.putTransformerFeature(
                builder,
                namespace("numerical_sequence", TensorFlowFeatureType.numericalSequence(2)),
                null
        );

        Feature feature = builder.getFeatureMap().get("numerical_sequence");
        assertEquals(List.of(0.0f, 0.0f), feature.getFloatList().getValueList());
    }

    @Test
    void wrongVectorLengthStillFails() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TensorFlowExampleEncoder.putTransformerFeature(
                        Features.newBuilder(),
                        namespace("categorical_sequence", TensorFlowFeatureType.categoricalSequence(3)),
                        new int[]{1, 2}
                )
        );

        assertEquals("Feature 'categorical_sequence' expected int64 vector length 3, got 2", exception.getMessage());
    }

    private static Namespace namespace(String name, TensorFlowFeatureType featureType) {
        return new Namespace() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public TensorFlowFeatureType getFeatureValueType() {
                return featureType;
            }

            public int compareTo(Namespace other) {
                return getName().compareTo(other.getName());
            }
        };
    }
}
