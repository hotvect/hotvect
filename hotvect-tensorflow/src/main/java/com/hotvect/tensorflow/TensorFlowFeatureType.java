package com.hotvect.tensorflow;

import com.hotvect.api.data.ValueType;
import org.tensorflow.types.family.TType;
import org.tensorflow.types.TFloat32;
import org.tensorflow.types.TInt64;
import org.tensorflow.types.TString;

/**
 * TensorFlow-specific feature types that extend the HotVect feature type system.
 *
 * <p>This is a cross-language contract between:
 * <ul>
 *     <li>Java feature extraction (online + offline)</li>
 *     <li>TFRecord / {@code tf.train.Example} encoding</li>
 *     <li>Python TensorFlow ingestion (training + serving)</li>
 * </ul>
 *
 * <p>Canonical dtype mapping:
 * <ul>
 *     <li>categorical → {@code int64}</li>
 *     <li>numerical → {@code float32}</li>
 *     <li>string → {@code string}</li>
 * </ul>
 */
public sealed interface TensorFlowFeatureType extends ValueType
        permits TensorFlowFeatureType.Categorical,
                TensorFlowFeatureType.Numerical,
                TensorFlowFeatureType.CategoricalSequence,
                TensorFlowFeatureType.NumericalSequence,
                TensorFlowFeatureType.StringType {

    TensorFlowFeatureType CATEGORICAL = new Categorical();
    TensorFlowFeatureType NUMERICAL = new Numerical();
    TensorFlowFeatureType STRING = new StringType();

    String schemaTypeName();

    /**
     * Canonical TensorFlow dtype name used in Python (e.g. {@code "int64"}, {@code "float32"}, {@code "string"}).
     */
    String tensorflowDTypeName();

    /**
     * Canonical TensorFlow tensor type interface used by tensorflow-core-api (e.g. {@link TInt64}).
     */
    Class<? extends TType> tensorflowTensorType();

    /**
     * Tensor shape for a single feature:
     * <ul>
     *     <li>scalars → {@code []}</li>
     *     <li>1D fixed-length sequences → {@code [L]}</li>
     * </ul>
     */
    int[] shape();

    default boolean isSequence() {
        return shape().length != 0;
    }

    static TensorFlowFeatureType categoricalSequence(int length) {
        return new CategoricalSequence(length);
    }

    static TensorFlowFeatureType numericalSequence(int length) {
        return new NumericalSequence(length);
    }

    /**
     * Maps a plain Java type to the corresponding {@link TensorFlowFeatureType}.
     *
     * <p>Note: fixed-length sequence types require a per-feature length, so array
     * types are intentionally not mapped here.
     */
    static ValueType fromJavaType(Class<?> javaType) {
        if (javaType == null) {
            return null;
        }

        // CATEGORICAL
        if (javaType == Integer.class || javaType == int.class || javaType == Long.class || javaType == long.class) {
            return CATEGORICAL;
        }

        // NUMERICAL
        if (javaType == Float.class || javaType == float.class || javaType == Double.class || javaType == double.class) {
            return NUMERICAL;
        }

        // STRING
        if (javaType == String.class) {
            return STRING;
        }

        return null;
    }

    record Categorical() implements TensorFlowFeatureType {
        @Override
        public boolean hasNumericValues() {
            return false;
        }

        @Override
        public Class<?> getJavaType() {
            return int.class;
        }

        @Override
        public String schemaTypeName() {
            return "CATEGORICAL";
        }

        @Override
        public String tensorflowDTypeName() {
            return "int64";
        }

        @Override
        public Class<? extends TType> tensorflowTensorType() {
            return TInt64.class;
        }

        @Override
        public int[] shape() {
            return new int[0];
        }
    }

    record Numerical() implements TensorFlowFeatureType {
        @Override
        public boolean hasNumericValues() {
            return true;
        }

        @Override
        public Class<?> getJavaType() {
            return float.class;
        }

        @Override
        public String schemaTypeName() {
            return "NUMERICAL";
        }

        @Override
        public String tensorflowDTypeName() {
            return "float32";
        }

        @Override
        public Class<? extends TType> tensorflowTensorType() {
            return TFloat32.class;
        }

        @Override
        public int[] shape() {
            return new int[0];
        }
    }

    record StringType() implements TensorFlowFeatureType {
        @Override
        public boolean hasNumericValues() {
            return false;
        }

        @Override
        public Class<?> getJavaType() {
            return String.class;
        }

        @Override
        public String schemaTypeName() {
            return "STRING";
        }

        @Override
        public String tensorflowDTypeName() {
            return "string";
        }

        @Override
        public Class<? extends TType> tensorflowTensorType() {
            return TString.class;
        }

        @Override
        public int[] shape() {
            return new int[0];
        }
    }

    record CategoricalSequence(int length) implements TensorFlowFeatureType {
        public CategoricalSequence {
            if (length <= 0) {
                throw new IllegalArgumentException("CategoricalSequence length must be > 0 (was " + length + ")");
            }
        }

        @Override
        public boolean hasNumericValues() {
            return false;
        }

        @Override
        public Class<?> getJavaType() {
            return int[].class;
        }

        @Override
        public String schemaTypeName() {
            return "CATEGORICAL_SEQUENCE";
        }

        @Override
        public String tensorflowDTypeName() {
            return "int64";
        }

        @Override
        public Class<? extends TType> tensorflowTensorType() {
            return TInt64.class;
        }

        @Override
        public int[] shape() {
            return new int[]{length};
        }
    }

    record NumericalSequence(int length) implements TensorFlowFeatureType {
        public NumericalSequence {
            if (length <= 0) {
                throw new IllegalArgumentException("NumericalSequence length must be > 0 (was " + length + ")");
            }
        }

        @Override
        public boolean hasNumericValues() {
            return true;
        }

        @Override
        public Class<?> getJavaType() {
            return float[].class;
        }

        @Override
        public String schemaTypeName() {
            return "NUMERICAL_SEQUENCE";
        }

        @Override
        public String tensorflowDTypeName() {
            return "float32";
        }

        @Override
        public Class<? extends TType> tensorflowTensorType() {
            return TFloat32.class;
        }

        @Override
        public int[] shape() {
            return new int[]{length};
        }
    }
}
